"""Probe Telegram's documented test login without touching production sessions.

Run in an isolated environment with Telethon 1.45.0. Supply the application's
API credentials through AUTOGRAM_PROBE_API_ID and AUTOGRAM_PROBE_API_HASH.
No credentials, session keys, or account data are printed or persisted.
This is a diagnostic, not reviewer access provisioning or proof of compliance.
"""

import asyncio
import os
import secrets

from telethon import TelegramClient, errors


async def probe() -> int:
    client = TelegramClient(
        None,
        int(os.environ["AUTOGRAM_PROBE_API_ID"]),
        os.environ["AUTOGRAM_PROBE_API_HASH"],
        connection_retries=0,
        request_retries=0,
        flood_sleep_threshold=0,
        timeout=10,
    )
    # Fixed test DC endpoint from Telethon's official test-server documentation.
    # A fresh in-memory session cannot reuse the user's production account.
    client.session.set_dc(2, "149.154.167.40", 80)
    phone = "999662" + f"{secrets.randbelow(10000):04d}"
    try:
        await client.connect()
        print("TEST_DC_CONNECTED", flush=True)
        sent = await client.send_code_request(phone)
        print("TEST_CODE_REQUEST_ACCEPTED", flush=True)
        length = getattr(sent.type, "length", 5)
        if length not in (5, 6):
            print("UNSUPPORTED_TEST_CODE_LENGTH", flush=True)
            return 2
        # Telethon documents both five- and six-digit test codes. Bound the
        # probe to these two candidates; never retry a flood-limit response.
        for candidate_length in (length, 11 - length):
            try:
                await client.sign_in(
                    phone, "2" * candidate_length, phone_code_hash=sent.phone_code_hash
                )
                break
            except errors.PhoneNumberUnoccupiedError:
                # Do not register an account or accept terms in this probe.
                print("TEST_LOGIN_VALID_SIGNUP_REQUIRED", flush=True)
                return 0
            except errors.PhoneCodeInvalidError:
                print(f"TEST_CODE_REJECTED_LENGTH:{candidate_length}", flush=True)
        else:
            return 2
        print("TEST_LOGIN_VALID", flush=True)
        # The number namespace is public. Do not inspect pre-existing test data.
        await client.log_out()
        return 0
    except errors.RPCError as error:
        print("TEST_RPC_FAILURE:" + type(error).__name__, flush=True)
        return 2
    except (OSError, asyncio.TimeoutError) as error:
        print("TEST_CONNECTION_FAILURE:" + type(error).__name__, flush=True)
        return 2
    finally:
        await client.disconnect()


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(asyncio.wait_for(probe(), timeout=45)))
    except asyncio.TimeoutError:
        print("TEST_PROBE_TIMEOUT", flush=True)
        raise SystemExit(2)
