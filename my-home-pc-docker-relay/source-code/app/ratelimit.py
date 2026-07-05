"""Throttle repeated auth failures, keyed by client IP.

In-memory on purpose: a restart forgetting the counters is acceptable (an attacker
gains at most one fresh window), and it keeps this dependency- and state-file-free.
A source that keeps failing gets locked out entirely — even a correct token is
rejected during the lockout, so the counter can't be used as a guessing oracle.
"""
import os
import time

# Read once at import, like RELAY_ENABLE_DOCS in main.py — env changes need a
# container restart anyway.
MAX_FAILURES = int(os.environ.get("RELAY_AUTH_MAX_FAILURES", "5"))
LOCKOUT_SECONDS = float(os.environ.get("RELAY_AUTH_LOCKOUT_SECONDS", "300"))

# ip -> (consecutive_failures, monotonic time of last failure)
_failures: dict[str, tuple[int, float]] = {}


def _expired(last_failure: float, now: float) -> bool:
    return now - last_failure > LOCKOUT_SECONDS


def is_locked_out(ip: str) -> bool:
    now = time.monotonic()
    entry = _failures.get(ip)
    if entry is None:
        return False
    count, last_failure = entry
    if _expired(last_failure, now):
        del _failures[ip]
        return False
    return count >= MAX_FAILURES


def record_failure(ip: str) -> None:
    now = time.monotonic()
    entry = _failures.get(ip)
    if entry is None or _expired(entry[1], now):
        _failures[ip] = (1, now)
    else:
        _failures[ip] = (entry[0] + 1, now)
    # Opportunistic prune so long-gone IPs don't accumulate forever.
    for stale_ip in [k for k, (_, last) in _failures.items() if _expired(last, now)]:
        del _failures[stale_ip]


def clear(ip: str) -> None:
    _failures.pop(ip, None)


def reset() -> None:
    """Test helper: forget all state."""
    _failures.clear()
