"""Offline native-CLI contract tests; no database or HTTP server is contacted."""

import copy
import hashlib
import io
import json
import re
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

import after_sales_eval_fixture as helper
import after_sales_eval_db as db


class FakeMysql:
    """Understand only the adapter's constrained native SELECT/age operations."""

    def __init__(self):
        self.calls = []
        self.now = datetime(2026, 10, 1, 10, 0, 0)
        self.database = "fixture_db"
        self.indexes = [["uk_refund_order", "0", "1", "order_id", "NULL"]]
        self.orders = {
            "9007199254741000": {"owner": "9007199254740993", "number": "NO-1",
                "status": "RECEIVED", "amount": "39.80", "created": self.now},
            "9007199254741001": {"owner": "9007199254740994", "number": "NO-2",
                "status": "PAID", "amount": "39.8", "created": self.now}}
        self.refunds = {}
        self.update_count = 0
        self.after_update = None
        self.failure = None

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if self.failure:
            raise self.failure
        assert isinstance(argv, list) and kwargs.get("shell") is False
        assert kwargs["capture_output"] and kwargs["text"]
        sql = kwargs["input"].strip()
        if sql.startswith("SELECT DATABASE()"):
            rows = [[self.database, self.now.isoformat(timespec="microseconds")]]
        elif "information_schema.statistics" in sql:
            rows = self.indexes
        elif sql.startswith("SELECT CAST(id AS CHAR)"):
            oid = re.search(r"WHERE id = ([0-9]+);$", sql).group(1)
            order = self.orders.get(oid)
            rows = [] if order is None else [[oid, order["owner"], order["number"], order["status"],
                order["amount"], order["created"].isoformat(timespec="seconds"),
                self.now.isoformat(timespec="microseconds"),
                str(int((self.now - order["created"]).total_seconds()))]]
        elif sql.startswith("SELECT status, CAST(amount AS CHAR)"):
            oid = re.search(r"WHERE order_id = ([0-9]+) ORDER BY id;$", sql).group(1)
            rows = self.refunds.get(oid, [])
        elif sql.startswith("UPDATE `order` SET created_at"):
            match = re.fullmatch(r"UPDATE `order` SET created_at = DATE_SUB\(NOW\(\), INTERVAL ([0-9]+) SECOND\) WHERE id = ([0-9]+) AND user_id = ([0-9]+);\s*SELECT ROW_COUNT\(\);", sql)
            assert match, "SQL exceeds fixed age-update contract"
            seconds, oid, owner = match.groups()
            order = self.orders.get(oid)
            count = 0
            if order and order["owner"] == owner:
                target = self.now.replace(microsecond=0) - timedelta(seconds=int(seconds))
                count = int(order["created"] != target)
                order["created"] = target
                self.update_count += 1
            if self.after_update:
                self.after_update(self)
            rows = [[str(count)]]
        else:
            raise AssertionError("unexpected SQL operation")
        return subprocess.CompletedProcess(argv, 0,
            "".join("\t".join(row) + "\n" for row in rows), "")


class DatabaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "run-001/NORMAL-001/trial-001/ledger.json"
        self.path.parent.mkdir(parents=True)
        self.env = {"AFTER_SALES_EVAL_OUTPUT_ROOT": str(self.root),
            "SPRING_DATASOURCE_URL": "jdbc:mysql://localhost:3307/fixture_db?serverTimezone=Asia/Shanghai",
            "SPRING_DATASOURCE_USERNAME": "fixture-user",
            "SPRING_DATASOURCE_PASSWORD": "private-db-password",
            "MYSQL_EXE": "D:/MySQL/MySQL Server 8.0/bin/mysql.exe",
            "PATH": "native-path", "SystemRoot": "C:/Windows",
            "MODEL_API_KEY": "never-forward", "MERCHANT_JWT_SECRET": "never-forward"}
        fixture = {"activeActor": "actor-a", "actors": ["actor-a", "actor-b"], "products": {},
            "orders": {alias: {"owner": owner, "ageSeconds": 0, "status": status,
                "expectedPaidAmount": "39.800", "existingRefund": "NONE", "items": []}
                for alias, owner, status in (("order-a", "actor-a", "RECEIVED"), ("order-b", "actor-b", "PAID"))}}
        self.ledger = {"schemaVersion": 1, "runId": "run-001", "caseId": "NORMAL-001", "trialId": "trial-001",
            "status": "PREPARED", "fixture": fixture, "actors": {
                "actor-a": {"userId": "9007199254740993", "userToken": "private-a"},
                "actor-b": {"userId": "9007199254740994", "userToken": "private-b"}},
            "orders": {alias: {"orderId": oid, "orderNo": number, "owner": owner,
                "expectedPaidAmount": "39.800", "requestedAgeSeconds": 0,
                "requestedStatus": status, "existingRefund": "NONE"}
                for alias, oid, number, owner, status in (
                    ("order-a", "9007199254741000", "NO-1", "actor-a", "RECEIVED"),
                    ("order-b", "9007199254741001", "NO-2", "actor-b", "PAID"))},
            "operations": [
                {"alias": "actor-a", "action": "REGISTER", "state": "COMPLETED", "receipt": {"data": {"id": "9007199254740993"}}},
                {"alias": "actor-b", "action": "REGISTER", "state": "COMPLETED", "receipt": {"data": {"id": "9007199254740994"}}},
                {"alias": "order-a", "action": "ORDER", "state": "COMPLETED", "receipt": {"data": {"id": "9007199254741000", "userId": "9007199254740993"}}},
                {"alias": "order-b", "action": "ORDER", "state": "COMPLETED", "receipt": {"data": {"id": "9007199254741001", "userId": "9007199254740994"}}}]}
        self.save()
        self.cli = FakeMysql()
        self.runner = patch.object(db.subprocess, "run", self.cli)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def save(self):
        self.ledger["fixtureHash"] = hashlib.sha256(json.dumps(self.ledger["fixture"], sort_keys=True,
            separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        self.path.write_text(json.dumps(self.ledger), encoding="utf-8")

    def age(self, seconds=86400):
        self.ledger["orders"]["order-a"]["requestedAgeSeconds"] = seconds
        self.ledger["fixture"]["orders"]["order-a"]["ageSeconds"] = seconds
        self.save()
        db.age_order(self.path, "order-a", seconds, self.env)

    def test_native_arguments_and_password_environment_are_isolated(self):
        self.assertEqual({"ready": True}, db.preflight(self.env))
        for argv, options in self.cli.calls:
            self.assertEqual(self.env["MYSQL_EXE"], argv[0])
            self.assertIn("--no-defaults", argv)
            self.assertIn("--host=localhost", argv)
            self.assertIn("--port=3307", argv)
            self.assertIn("--database=fixture_db", argv)
            self.assertIn("--user=fixture-user", argv)
            self.assertNotIn("private-db-password", json.dumps(argv))
            self.assertEqual("private-db-password", options["env"]["MYSQL_PWD"])
            self.assertNotIn("MODEL_API_KEY", options["env"])
            self.assertNotIn("MERCHANT_JWT_SECRET", options["env"])
            self.assertEqual("native-path", options["env"]["PATH"])
            self.assertTrue(options["env"]["MYSQL_TEST_LOGIN_FILE"].endswith("absent.cnf"))
            self.assertFalse(Path(options["env"]["MYSQL_TEST_LOGIN_FILE"]).exists())

    def test_mysql_default_is_discovered_on_path(self):
        env = dict(self.env)
        env.pop("MYSQL_EXE")
        with patch.object(db.shutil, "which", return_value="C:/Native/mysql.exe") as finder:
            db.preflight(env)
        finder.assert_called_once_with("mysql", path="native-path")
        self.assertEqual("C:/Native/mysql.exe", self.cli.calls[0][0][0])

    def test_missing_mysql_or_password_fails_without_query(self):
        env = dict(self.env)
        env.pop("MYSQL_EXE")
        with patch.object(db.shutil, "which", return_value=None):
            with self.assertRaises(helper.FixtureError):
                db.preflight(env)
        env = dict(self.env)
        env.pop("SPRING_DATASOURCE_PASSWORD")
        with self.assertRaises(helper.FixtureError):
            db.preflight(env)
        self.assertEqual([], self.cli.calls)

    def test_datasource_defaults_come_from_actual_backend_configuration(self):
        env = dict(self.env)
        env.pop("SPRING_DATASOURCE_URL")
        env.pop("SPRING_DATASOURCE_USERNAME")
        self.cli.database = "mall"
        db.preflight(env)
        self.assertIn("--database=mall", self.cli.calls[0][0])
        self.assertIn("--user=root", self.cli.calls[0][0])

    def test_malformed_datasource_or_actual_database_mismatch_is_rejected(self):
        for url in ("jdbc:mysql://localhost/db;DROP", "jdbc:mysql://localhost/a/b",
                    "jdbc:mysql://user:secret@localhost/mall", "jdbc:mysql://localhost/mall?password=secret",
                    "jdbc:mysql://localhost:0/mall", "jdbc:mysql://localhost/%6dall"):
            with self.subTest(url=url), self.assertRaises(helper.FixtureError):
                db.preflight(dict(self.env, SPRING_DATASOURCE_URL=url))
        self.assertEqual([], self.cli.calls)
        self.cli.database = "other_database"
        with self.assertRaises(helper.FixtureError):
            db.preflight(self.env)

    def test_preflight_requires_actual_single_column_unique_order_index(self):
        malformed = [[], [["uk", "1", "1", "order_id", "NULL"]],
            [["uk", "0", "1", "user_id", "NULL"]],
            [["uk", "0", "1", "order_id", "NULL"], ["uk", "0", "2", "status", "NULL"]],
            [["uk", "0", "2", "order_id", "NULL"]],
            [["uk", "0", "1", "order_id", "4"]],
            [["uk", "0", "1", "order_id", "NULL"], ["uk", "1", "1", "order_id", "NULL"]]]
        for indexes in malformed:
            with self.subTest(indexes=indexes):
                self.cli.indexes = indexes
                with self.assertRaises(helper.FixtureError):
                    db.preflight(self.env)
        self.cli.indexes = [["PRIMARY", "0", "1", "id", "NULL"], ["uk", "0", "1", "order_id", "NULL"]]
        self.assertEqual({"ready": True}, db.preflight(self.env))

    def test_native_failure_text_is_not_exposed_or_retried(self):
        self.cli.failure = subprocess.CalledProcessError(1, ["mysql"], stderr="private-db-password SQL private-id")
        with self.assertRaises(helper.FixtureError) as raised:
            db.preflight(self.env)
        self.assertEqual("", str(raised.exception))
        self.assertEqual(1, len(self.cli.calls))

    def test_age_update_requires_ledger_and_owner_match(self):
        self.cli.orders["9007199254741000"]["owner"] = "9007199254740994"
        with self.assertRaises(helper.FixtureError):
            self.age()
        self.assertEqual(0, self.cli.update_count)
        self.cli.orders["9007199254741000"]["owner"] = "9007199254740993"
        self.age()
        self.assertEqual(datetime(2026, 9, 30, 10, 0, 0), self.cli.orders["9007199254741000"]["created"])
        evidence = json.loads(self.path.read_text())["ageEvidence"]["order-a"]
        self.assertEqual("COMPLETED", evidence["status"])
        self.assertEqual(86400, evidence["actualAgeSeconds"])
        self.assertEqual("2026-10-01T10:00:00.000000", evidence["backendTimeAfter"])

    def test_age_rejects_outside_scope_identity_alias_and_nondecimal_ids(self):
        self.ledger["orders"]["order-a"]["requestedAgeSeconds"] = 86400
        self.ledger["fixture"]["orders"]["order-a"]["ageSeconds"] = 86400
        for mutation in (lambda: self.ledger.update(trialId="another-trial"),
                         lambda: self.ledger["orders"]["order-a"].update(orderId="1 OR 1=1"),
                         lambda: self.ledger["actors"]["actor-a"].update(userId="01"),
                         lambda: self.ledger["orders"]["order-a"].update(owner="actor-b"),
                         lambda: self.ledger["operations"].pop(2)):
            baseline = copy.deepcopy(self.ledger)
            mutation()
            self.save()
            with self.assertRaises(helper.FixtureError):
                db.age_order(self.path, "order-a", 86400, self.env)
            self.ledger = baseline
        self.save()
        with self.assertRaises(helper.FixtureError):
            db.age_order(self.path, "undeclared", 86400, self.env)
        outside = self.root / "ledger.json"
        outside.write_text(self.path.read_text())
        with self.assertRaises(helper.FixtureError):
            db.age_order(outside, "order-a", 86400, self.env)
        self.assertEqual([], self.cli.calls)

    def test_age_requires_exact_row_amount_and_declared_request(self):
        with self.assertRaises(helper.FixtureError):
            db.age_order(self.path, "order-a", 86401, self.env)
        self.cli.orders["9007199254741000"]["amount"] = "39.79"
        with self.assertRaises(helper.FixtureError):
            self.age()
        self.cli.orders.pop("9007199254741000")
        with self.assertRaises(helper.FixtureError):
            self.age()
        self.assertEqual(0, self.cli.update_count)

    def test_age_margin_applies_to_eight_day_transition_not_every_day(self):
        for seconds in (0, 86400, 12 * 86400, 8 * 86400 - 600, 8 * 86400 + 600):
            with self.subTest(seconds=seconds):
                self.age(seconds)
        previous = self.cli.update_count
        for seconds in (8 * 86400 - 599, 8 * 86400, 8 * 86400 + 599):
            with self.subTest(seconds=seconds), self.assertRaises(helper.FixtureError):
                self.age(seconds)
        self.assertEqual(previous, self.cli.update_count)

    def test_age_post_update_drift_failure_retains_private_error_evidence(self):
        self.cli.after_update = lambda cli: setattr(cli, "now", cli.now + timedelta(seconds=1201))
        with self.assertRaises(helper.FixtureError):
            self.age(8 * 86400 - 1200)
        evidence = json.loads(self.path.read_text())["ageEvidence"]["order-a"]
        self.assertEqual("ERROR", evidence["status"])
        self.assertEqual("FIXTURE_ERROR", evidence["errorCategory"])
        self.assertEqual(1, self.cli.update_count)

    def test_oracle_projects_decimal_amount_without_ids(self):
        self.cli.refunds["9007199254741000"] = [["REFUNDED", "39.8000", "9007199254740993"]]
        self.cli.orders["9007199254741000"]["status"] = "REFUNDED"
        result = db.oracle(self.path, "COMPLETED", self.env)
        self.assertEqual({"orders": {
            "order-a": {"orderStatus": "REFUNDED", "paidAmount": "39.80", "refundRows": [
                {"status": "REFUNDED", "amount": "39.8000", "ownerMatches": True}], "ownerMatches": True},
            "order-b": {"orderStatus": "PAID", "paidAmount": "39.8", "refundRows": [], "ownerMatches": False}},
            "terminalEvidence": "COMPLETED"}, result)
        self.assertEqual(Decimal("39.8"), Decimal(result["orders"]["order-a"]["paidAmount"]))
        self.assertNotIn("900719925474", json.dumps(result))
        self.assertNotIn("private", json.dumps(result))

    def test_foreign_fixture_owner_and_refund_owner_are_compared_to_active_actor(self):
        self.cli.refunds["9007199254741001"] = [["PENDING", "39.80", "9007199254740994"]]
        result = db.oracle(self.path, "NOT_SENT", self.env)
        self.assertFalse(result["orders"]["order-b"]["ownerMatches"])
        self.assertFalse(result["orders"]["order-b"]["refundRows"][0]["ownerMatches"])
        self.cli.orders["9007199254741001"]["owner"] = "9007199254740993"
        self.cli.refunds["9007199254741001"][0][2] = "9007199254740993"
        result = db.oracle(self.path, "NOT_SENT", self.env)
        self.assertTrue(result["orders"]["order-b"]["ownerMatches"])
        self.assertTrue(result["orders"]["order-b"]["refundRows"][0]["ownerMatches"])

    def test_oracle_active_actor_switch_and_refund_owner_are_independent(self):
        self.cli.refunds["9007199254741001"]=[["PENDING","39.80","9007199254740993"]]
        result=db.oracle(self.path,"NOT_SENT",self.env)
        self.assertFalse(result['orders']['order-b']['ownerMatches'])
        self.assertTrue(result['orders']['order-b']['refundRows'][0]['ownerMatches'])
        self.ledger['fixture']['activeActor']='actor-b'; self.save()
        result=db.oracle(self.path,"NOT_SENT",self.env)
        self.assertFalse(result['orders']['order-a']['ownerMatches'])
        self.assertTrue(result['orders']['order-b']['ownerMatches'])
        self.assertFalse(result['orders']['order-b']['refundRows'][0]['ownerMatches'])

    def test_oracle_active_actor_requires_declared_identity_and_registration_receipt(self):
        self.ledger['fixture']['orders'].pop('order-a')
        self.ledger['orders'].pop('order-a')
        self.ledger['operations']=[entry for entry in self.ledger['operations'] if not (entry['action']=='ORDER' and entry['alias']=='order-a')]
        for mutation in ('undeclared','wrong-id','missing-registration'):
            with self.subTest(mutation=mutation):
                baseline=copy.deepcopy(self.ledger)
                if mutation=='undeclared': self.ledger['fixture']['activeActor']='actor-c'
                if mutation=='wrong-id': self.ledger['actors']['actor-a']['userId']='9007199254740995'
                if mutation=='missing-registration': self.ledger['operations'].pop(0)
                self.save()
                with self.assertRaises(helper.FixtureError): db.oracle(self.path,'NOT_SENT',self.env)
                self.ledger=baseline
        self.save()
        self.assertEqual([],self.cli.calls)

    def test_zero_rows_with_unknown_request_is_not_terminal(self):
        for terminal in ("UNKNOWN", "NOT_SENT", "COMPLETED"):
            self.assertEqual(terminal, db.oracle(self.path, terminal, self.env)["terminalEvidence"])
        with self.assertRaises(helper.FixtureError):
            db.oracle(self.path, "SAFE_NO_WRITE", self.env)

    def test_oracle_rejects_missing_order_invalid_money_or_status(self):
        for field, value in (("amount", "3.98e1"), ("status", "APPROVED")):
            original = self.cli.orders["9007199254741000"][field]
            self.cli.orders["9007199254741000"][field] = value
            with self.subTest(field=field), self.assertRaises(helper.FixtureError):
                db.oracle(self.path, "UNKNOWN", self.env)
            self.cli.orders["9007199254741000"][field] = original
        self.cli.orders.pop("9007199254741001")
        with self.assertRaises(helper.FixtureError):
            db.oracle(self.path, "UNKNOWN", self.env)

    def test_oracle_rechecks_age_boundary_after_worker_elapsed_time(self):
        self.age(8 * 86400 - 1200)
        self.cli.now += timedelta(seconds=601)
        with self.assertRaises(helper.FixtureError):
            db.oracle(self.path, "UNKNOWN", self.env)

    def test_oracle_requires_successful_nonzero_age_preparation_evidence(self):
        self.ledger["fixture"]["orders"]["order-a"]["ageSeconds"] = 86400
        self.ledger["orders"]["order-a"]["requestedAgeSeconds"] = 86400
        self.save()
        with self.assertRaises(helper.FixtureError):
            db.oracle(self.path, "UNKNOWN", self.env)

    def test_oracle_reports_actual_mismatched_amount_instead_of_expected_fixture_amount(self):
        self.cli.orders["9007199254741000"]["amount"] = "30.00"
        result = db.oracle(self.path, "UNKNOWN", self.env)
        self.assertEqual("30.00", result["orders"]["order-a"]["paidAmount"])

    def test_malformed_native_output_exit_failure_and_timeout_are_fixed_errors(self):
        for result in (subprocess.CompletedProcess(["mysql"], 1, "private-output", "private-db-password"),
                       subprocess.CompletedProcess(["mysql"], 0, "wrong-column-count\n", "")):
            with self.subTest(result=result.returncode), patch.object(db.subprocess, "run", return_value=result):
                with self.assertRaises(helper.FixtureError) as error:
                    db.preflight(self.env)
                self.assertEqual("", str(error.exception))
        self.cli.failure = subprocess.TimeoutExpired(["mysql"], 30, output="private-db-password")
        with self.assertRaises(helper.FixtureError) as error:
            db.preflight(self.env)
        self.assertEqual("", str(error.exception))

    def test_age_refuses_other_field_change_during_update(self):
        self.cli.after_update = lambda cli: cli.orders["9007199254741000"].update(status="REFUNDED")
        with self.assertRaises(helper.FixtureError):
            self.age()
        self.assertEqual("ERROR", json.loads(self.path.read_text())["ageEvidence"]["order-a"]["status"])

    def test_unchanged_age_update_is_not_mistaken_for_missing_order(self):
        self.cli.orders["9007199254741000"]["created"] = self.cli.now - timedelta(seconds=86400)
        self.age()
        self.assertEqual(0, json.loads(self.path.read_text())["ageEvidence"]["order-a"]["updatedRows"])

    def test_real_helper_ledger_with_two_actors_integrates_with_age_and_oracle(self):
        from test_after_sales_eval_fixture import FakeBusinessApi, request
        api = FakeBusinessApi(self.root)
        self.cli.orders.clear()
        native = self.cli
        def mysql_with_business_rows(argv, **kwargs):
            for oid, order in api.orders.items():
                row = native.orders.setdefault(oid, {"created": native.now})
                row.update(owner=order["userId"], number=order["orderNo"],
                    status=order["status"], amount=order["totalAmount"])
            return native(argv, **kwargs)
        req = request(trial="integrated-trial")
        req["fixture"]["actors"].append("actor-b")
        req["fixture"]["orders"]["order-a"]["ageSeconds"] = 864000
        req["fixture"]["orders"]["order-b"] = {"owner": "actor-b", "status": "PAID", "ageSeconds": 86400,
            "items": [{"skuAlias": "sku-a", "quantity": 2}], "expectedPaidAmount": "39.8", "existingRefund": "NONE"}
        env = dict(self.env, DEMO_MERCHANT_TOKEN="merchant-secret", SUPERMALL_BASE_URL="http://fixture.invalid")
        with patch.object(helper, "urlopen", api), patch.object(db.subprocess, "run", mysql_with_business_rows):
            reply = helper.prepare(req, env)
            self.assertEqual("COMPLETED", reply["status"])
            ledger = json.loads((self.root / reply["ledgerPath"]).read_text())
            self.assertEqual({"order-a", "order-b"}, set(ledger["ageEvidence"]))
            result = helper.oracle(self.root / reply["ledgerPath"], "UNKNOWN", env)
        self.assertEqual("UNKNOWN", result["terminalEvidence"])
        self.assertTrue(result['orders']['order-a']['ownerMatches'])
        self.assertFalse(result['orders']['order-b']['ownerMatches'])
        self.assertEqual(2, self.cli.update_count)

    def test_helper_wire_exports_actual_oracle_and_keeps_probe_unsupported(self):
        request = {"schemaVersion": 1, "op": "oracle", "runId": "run-001", "caseId": "NORMAL-001",
            "trialId": "trial-001", "ledgerPath": "run-001/NORMAL-001/trial-001/ledger.json", "terminalEvidence": "UNKNOWN"}
        output = io.StringIO()
        helper.serve(io.StringIO(json.dumps(request) + "\n"), output, self.env)
        reply = json.loads(output.getvalue())
        self.assertEqual("COMPLETED", reply["status"])
        self.assertEqual({"order-a", "order-b"}, set(reply["oracle"]["orders"]))
        self.assertNotIn("900719925474", output.getvalue())
        request.pop("terminalEvidence")
        request.update(op="probe", probe="ROLLBACK_AFTER_INSERT")
        output = io.StringIO()
        helper.serve(io.StringIO(json.dumps(request) + "\n"), output, self.env)
        self.assertEqual({"schemaVersion": 1, "op": "probe", "status": "ERROR", "errorCategory": "FIXTURE_ERROR"}, json.loads(output.getvalue()))


if __name__ == "__main__":
    unittest.main()
