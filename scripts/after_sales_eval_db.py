"""Private fixture-only MySQL adapter: fixed reads and scoped created_at updates.

The business Agent never imports this module or receives its environment. SQL is
internal; IDs are validated decimal strings from a confined helper ledger.
"""

import hashlib
import json
import math
import re
import shutil
import subprocess
import tempfile
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

import after_sales_eval_fixture as fixture


POLICY_BOUNDARY_SECONDS = 8 * 86400  # RefundEligibilityEvaluator uses complete 24h days.
AGE_MARGIN_SECONDS = 600
RUNTIME_KEYS = {"PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP"}
_ORDER_SQL = (
    "SELECT CAST(id AS CHAR), CAST(user_id AS CHAR), order_no, status, "
    "CAST(total_amount AS CHAR), DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s'), "
    "DATE_FORMAT(NOW(6), '%Y-%m-%dT%H:%i:%s.%f'), "
    "TIMESTAMPDIFF(SECOND, created_at, NOW(6)) FROM `order` WHERE id = {order_id};")


def _id(value):
    # Do not coerce arbitrary protocol/ledger JSON numbers into business identity.
    return fixture._pattern(value, re.compile(r"[1-9][0-9]*"))


def _datasource(environment):
    config = Path(__file__).resolve().parents[1] / "mall-server/src/main/resources/application.yml"
    text = config.read_text(encoding="utf-8-sig")
    block = re.search(r"(?m)^  datasource:\s*\n((?:    .*\n|\n)+)", text)
    if not block:
        raise fixture.FixtureError()
    def setting(key):
        value = environment.get("SPRING_DATASOURCE_" + key.upper())
        if value is None:
            match = re.search(r"(?m)^    " + key + r": (.+)$", block.group(1))
            if not match:
                raise fixture.FixtureError()
            value = match.group(1).strip()
        return fixture._text(value)
    jdbc = setting("url")
    if not jdbc.startswith("jdbc:mysql://"):
        raise fixture.FixtureError()
    parsed = urlsplit(jdbc.removeprefix("jdbc:"))
    host = parsed.hostname
    if (not host or not re.fullmatch(r"[A-Za-z0-9.-]+", host)
            or parsed.username is not None or parsed.password is not None or parsed.fragment):
        raise fixture.FixtureError()
    port = parsed.port if parsed.port is not None else 3306
    if not 1 <= port <= 65535:
        raise fixture.FixtureError()
    database = fixture._pattern(parsed.path.removeprefix("/"), re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}"))
    # JDBC connection properties cannot provide a second credentials channel.
    if any(key.lower() in ("user", "username", "password", "password1", "password2", "password3")
           for key, _ in parse_qsl(parsed.query, keep_blank_values=True)):
        raise fixture.FixtureError()
    username = fixture._pattern(setting("username"), re.compile(r"[A-Za-z0-9_.@%-]+"))
    password = fixture._text(environment.get("SPRING_DATASOURCE_PASSWORD"))
    if password.startswith("${"):
        raise fixture.FixtureError()
    return host, port, database, username, password


class _Mysql:
    def __init__(self, environment):
        try:
            host, port, self.database, username, password = _datasource(environment)
            executable = environment.get("MYSQL_EXE") or shutil.which("mysql", path=environment.get("PATH", ""))
            if not executable or any(c in executable for c in "\x00\r\n"):
                raise fixture.FixtureError()
            self.argv = [executable, "--no-defaults", "--protocol=TCP", "--batch", "--raw",
                "--skip-column-names", "--default-character-set=utf8mb4", "--connect-timeout=10",
                "--host=" + host, "--port=" + str(port), "--user=" + username, "--database=" + self.database]
            self.environment = {k: v for k, v in environment.items() if k in RUNTIME_KEYS}
            self.environment["MYSQL_PWD"] = password
        except Exception:
            raise fixture.FixtureError() from None

    def query(self, sql, columns):
        try:
            # MySQL 8.0 reads .mylogin.cnf even with --no-defaults. Redirect that
            # file to an absent path in a private temporary directory; connection
            # settings then come only from the configured datasource/MYSQL_PWD.
            with tempfile.TemporaryDirectory(prefix="fixture-mysql-") as directory:
                child_env = dict(self.environment, MYSQL_TEST_LOGIN_FILE=str(Path(directory) / "absent.cnf"))
                result = subprocess.run(self.argv, input=sql + "\n", shell=False,
                    capture_output=True, text=True, encoding="utf-8", timeout=30, env=child_env)
            if result.returncode != 0 or not isinstance(result.stdout, str):
                raise fixture.FixtureError()
            rows = [line.split("\t") for line in result.stdout.splitlines()]
            if any(len(row) != columns for row in rows):
                raise fixture.FixtureError()
            return rows
        except Exception:
            # Native stderr, SQL, IDs and credentials are never exception text.
            raise fixture.FixtureError() from None

    def readiness(self):
        rows = self.query("SELECT DATABASE(), DATE_FORMAT(NOW(6), '%Y-%m-%dT%H:%i:%s.%f');", 2)
        if len(rows) != 1 or rows[0][0] != self.database:
            raise fixture.FixtureError()
        _time(rows[0][1])
        indexes = self.query(
            "SELECT index_name, non_unique, seq_in_index, column_name, sub_part "
            "FROM information_schema.statistics WHERE table_schema = DATABASE() "
            "AND table_name = 'refund' ORDER BY index_name, seq_in_index;", 5)
        grouped = {}
        for name, unique, sequence, column, prefix in indexes:
            grouped.setdefault(name, []).append((unique, sequence, column, prefix))
        if not any(rows == [("0", "1", "order_id", "NULL")] for rows in grouped.values()):
            raise fixture.FixtureError()


def preflight(environment: dict[str, str]) -> dict:
    client = _Mysql(environment)
    client.readiness()
    return {"ready": True}


def _load_ledger(ledger_path, environment):
    try:
        path = Path(ledger_path).resolve()
        ledger = fixture._read_json(path)
        if type(ledger.get("schemaVersion")) is not int or ledger["schemaVersion"] != 1:
            raise fixture.FixtureError()
        _, trial = fixture._scope(ledger, environment)
        if path != (trial / "ledger.json").resolve():
            raise fixture.FixtureError()
        declaration = ledger["fixture"]
        digest = hashlib.sha256(json.dumps(declaration, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False).encode()).hexdigest()
        if digest != ledger["fixtureHash"]:
            raise fixture.FixtureError()
        if not isinstance(ledger["orders"], dict) or not ledger["orders"]:
            raise fixture.FixtureError()
        active = fixture._pattern(declaration["activeActor"], fixture.ALIAS)
        if active not in declaration["actors"]:
            raise fixture.FixtureError()
        active_id = _id(ledger["actors"][active]["userId"])
        registrations = [entry for entry in ledger["operations"] if entry.get("action") == "REGISTER"
                         and entry.get("alias") == active and entry.get("state") == "COMPLETED"]
        if len(registrations) != 1 or fixture._decimal_id(registrations[0]["receipt"]["data"]["id"]) != active_id:
            raise fixture.FixtureError()
        seen = set()
        for alias, order in ledger["orders"].items():
            fixture._pattern(alias, fixture.ALIAS)
            expected = declaration["orders"][alias]
            owner = fixture._pattern(order["owner"], fixture.ALIAS)
            if owner != expected["owner"] or owner not in declaration["actors"]:
                raise fixture.FixtureError()
            oid, uid = _id(order["orderId"]), _id(ledger["actors"][owner]["userId"])
            if oid in seen:
                raise fixture.FixtureError()
            seen.add(oid)
            fixture._text(order["orderNo"])
            if (order["requestedAgeSeconds"] != expected["ageSeconds"]
                    or order["requestedStatus"] != expected["status"]
                    or order["existingRefund"] != expected["existingRefund"]
                    or fixture._money(order["expectedPaidAmount"]) != fixture._money(expected["expectedPaidAmount"])):
                raise fixture.FixtureError()
            fixture._integer(order["requestedAgeSeconds"])
            # Tie SQL IDs to successful API creations in this trial, including
            # foreign users created for adversarial cases.
            for action, target, wanted in (("REGISTER", owner, {"id": uid}),
                    ("ORDER", alias, {"id": oid, "userId": uid})):
                operations = [entry for entry in ledger["operations"]
                    if entry.get("action") == action and entry.get("alias") == target and entry.get("state") == "COMPLETED"]
                if len(operations) != 1:
                    raise fixture.FixtureError()
                receipt = operations[0]["receipt"]["data"]
                if any(fixture._decimal_id(receipt[key]) != value for key, value in wanted.items()):
                    raise fixture.FixtureError()
        return path, ledger
    except Exception:
        raise fixture.FixtureError() from None


def _time(value):
    try:
        result = datetime.fromisoformat(value)
        if result.tzinfo is not None:
            raise ValueError()
        return result
    except Exception:
        raise fixture.FixtureError() from None


def _order(client, order):
    rows = client.query(_ORDER_SQL.format(order_id=_id(order["orderId"])), 8)
    if len(rows) != 1:
        raise fixture.FixtureError()
    oid, uid, number, status, amount, created, now, age = rows[0]
    if _id(oid) != order["orderId"] or number != order["orderNo"] or status not in fixture.STATUSES:
        raise fixture.FixtureError()
    _id(uid)
    fixture._pattern(amount, fixture.MONEY)
    fixture._pattern(age, re.compile(r"-?(?:0|[1-9][0-9]*)"))
    actual_age = int(age)
    if int((_time(now) - _time(created)).total_seconds()) != actual_age:
        raise fixture.FixtureError()
    return {"ownerId": uid, "status": status, "amount": amount, "createdAt": created,
        "backendTime": now, "ageSeconds": actual_age}


def _age_valid(requested, actual):
    if (actual < 0 or abs(requested - POLICY_BOUNDARY_SECONDS) < AGE_MARGIN_SECONDS
            or abs(actual - POLICY_BOUNDARY_SECONDS) < AGE_MARGIN_SECONDS
            or (requested < POLICY_BOUNDARY_SECONDS) != (actual < POLICY_BOUNDARY_SECONDS)):
        raise fixture.FixtureError()


def age_order(ledger_path: Path, order_alias: str, age_seconds: int, environment: dict[str, str]) -> None:
    path, ledger = _load_ledger(ledger_path, environment)
    fixture._pattern(order_alias, fixture.ALIAS)
    fixture._integer(age_seconds)
    order = ledger["orders"].get(order_alias)
    if order is None or age_seconds != order["requestedAgeSeconds"]:
        raise fixture.FixtureError()
    _age_valid(age_seconds, age_seconds)
    client = _Mysql(environment)
    before = _order(client, order)
    owner_id = _id(ledger["actors"][order["owner"]]["userId"])
    if before["ownerId"] != owner_id or Decimal(before["amount"]) != fixture._money(order["expectedPaidAmount"]):
        raise fixture.FixtureError()
    evidence = {"status": "PREPARED", "requestedAgeSeconds": age_seconds,
        "backendTimeBefore": before["backendTime"], "createdAtBefore": before["createdAt"]}
    ledger.setdefault("ageEvidence", {})[order_alias] = evidence
    fixture._atomic_json(path, ledger)
    try:
        rows = client.query("UPDATE `order` SET created_at = DATE_SUB(NOW(), INTERVAL " + str(age_seconds)
            + " SECOND) WHERE id = " + _id(order["orderId"]) + " AND user_id = " + owner_id + ";\nSELECT ROW_COUNT();", 1)
        if len(rows) != 1 or rows[0][0] not in ("0", "1"):
            raise fixture.FixtureError()
        evidence["updatedRows"] = int(rows[0][0])
        after = _order(client, order)
        evidence.update(backendTimeAfter=after["backendTime"], createdAtAfter=after["createdAt"],
            actualAgeSeconds=after["ageSeconds"])
        elapsed = (_time(after["backendTime"]) - _time(before["backendTime"])).total_seconds()
        if (elapsed < 0 or not age_seconds <= after["ageSeconds"] <= age_seconds + math.ceil(elapsed) + 1
                or after["ownerId"] != owner_id or after["status"] != before["status"]
                or Decimal(after["amount"]) != Decimal(before["amount"])):
            raise fixture.FixtureError()
        _age_valid(age_seconds, after["ageSeconds"])
        evidence["status"] = "COMPLETED"
        fixture._atomic_json(path, ledger)
    except Exception:
        evidence.update(status="ERROR", errorCategory="FIXTURE_ERROR")
        fixture._atomic_json(path, ledger)
        raise fixture.FixtureError() from None


def oracle(ledger_path: Path, terminal_evidence: str, environment: dict[str, str]) -> dict:
    if terminal_evidence not in ("NOT_SENT", "COMPLETED", "UNKNOWN"):
        raise fixture.FixtureError()
    _, ledger = _load_ledger(ledger_path, environment)
    if set(ledger["orders"]) != set(ledger["fixture"]["orders"]):
        raise fixture.FixtureError()
    client = _Mysql(environment)
    result = {}
    active_owner = _id(ledger["actors"][ledger["fixture"]["activeActor"]]["userId"])
    for alias, order in ledger["orders"].items():
        actual = _order(client, order)
        evidence = ledger.get("ageEvidence", {}).get(alias)
        if order["requestedAgeSeconds"] and not evidence:
            raise fixture.FixtureError()
        if evidence:
            if (evidence["status"] != "COMPLETED" or actual["createdAt"] != evidence["createdAtAfter"]
                    or _time(actual["backendTime"]) < _time(evidence["backendTimeAfter"])):
                raise fixture.FixtureError()
            _age_valid(order["requestedAgeSeconds"], actual["ageSeconds"])
        refunds = client.query("SELECT status, CAST(amount AS CHAR), CAST(user_id AS CHAR) FROM refund "
            "WHERE order_id = " + _id(order["orderId"]) + " ORDER BY id;", 3)
        projected = []
        for status, amount, uid in refunds:
            if status not in ("PENDING", "REFUNDED"):
                raise fixture.FixtureError()
            fixture._pattern(amount, fixture.MONEY)
            projected.append({"status": status, "amount": amount, "ownerMatches": _id(uid) == active_owner})
        result[alias] = {"orderStatus": actual["status"], "paidAmount": actual["amount"],
            "refundRows": projected, "ownerMatches": actual["ownerId"] == active_owner}
    # A temporary absence of rows never downgrades an UNKNOWN submission.
    return {"orders": result, "terminalEvidence": terminal_evidence}
