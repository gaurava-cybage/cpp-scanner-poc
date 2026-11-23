"""Sanctions / AML screening invoked by PayHub (C++) via CreateProcess.

Usage: python sanctions_screen.py <debtor_acct> <creditor_acct> <creditor_agent> <amount_minor> <currency>
The process exit code is the match score, 0 (clear) to 100 (confirmed hit).
"""
import sys

WATCHLIST_ACCOUNTS = {"999900001111", "999900002222"}
WATCHLIST_AGENT_PREFIXES = ("XXBK", "BLKD")
HIGH_VALUE_MINOR = 1_000_000_000   # 1 crore INR in paise


def screen(debtor: str, creditor: str, agent: str, amount_minor: int, currency: str) -> int:
    score = 0
    if debtor in WATCHLIST_ACCOUNTS or creditor in WATCHLIST_ACCOUNTS:
        score += 90
    if agent.startswith(WATCHLIST_AGENT_PREFIXES):
        score += 80
    if currency != "INR" and amount_minor > HIGH_VALUE_MINOR // 100:
        score += 20
    if amount_minor > HIGH_VALUE_MINOR:
        score += 10
    return min(score, 100)


def main() -> int:
    if len(sys.argv) != 6:
        return 100
    return screen(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5])


if __name__ == "__main__":
    sys.exit(main())
