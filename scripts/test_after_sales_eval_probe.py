"""Offline probe boundary tests: native processes and business HTTP are fakes."""

import copy
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import after_sales_eval_fixture as helper
import after_sales_eval_db as db
from test_after_sales_eval_fixture import FakeBusinessApi, request
from test_after_sales_eval_db import FakeMysql


METHODS = {
    "ROLLBACK_AFTER_INSERT": "rollbackAfterInsert",
    "CONCURRENT_IDEMPOTENCY": "concurrentIdempotency",
    "LEGACY_PENDING": "legacyPendingDoesNotMeanRefunded",
    "STALE_POLICY": "stalePolicyDoesNotWrite",
}


class ProbeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {"AFTER_SALES_EVAL_OUTPUT_ROOT": str(self.root),
            "AFTER_SALES_EVAL_DB_TEST": "true", "DEMO_MERCHANT_TOKEN": "merchant-secret",
            "SPRING_DATASOURCE_URL": "jdbc:mysql://localhost/fixture_db",
            "SPRING_DATASOURCE_USERNAME": "fixture-user", "SPRING_DATASOURCE_PASSWORD": "db-secret",
            "MYSQL_EXE": "C:/Native/mysql.exe", "JAVA_HOME": "C:/JDK 22",
            "AFTER_SALES_EVAL_MAVEN_EXE": "C:/IDE Maven/bin/mvn.cmd",
            "MALL_WORKER_ID": "3", "MALL_DATACENTER_ID": "4",
            "MERCHANT_JWT_SECRET": "backend-signing-secret", "PATH": "native-path",
            "SystemRoot": "C:/Windows", "MODEL_API_KEY": "never-forward"}
        self.api = FakeBusinessApi(self.root)
        self.mysql = FakeMysql()
        self.jvm_calls = []
        self.java_calls = []
        self.failure = None
        self.bad_evidence = None
        self.clock_shift = 0
        self.addCleanup(patch.stopall)
        patch.object(helper, "urlopen", self.api).start()
        patch.object(subprocess, "run", self.native).start()
        self.new_fixture("trial-001")

    def new_fixture(self, trial):
        # Each test invocation gets fresh fake business identities and a ledger.
        prepare_env = dict(self.env)
        prepare_env.pop("AFTER_SALES_EVAL_DB_TEST")
        self.prepared = helper.prepare(request(trial=trial), prepare_env)
        self.assertEqual("COMPLETED", self.prepared["status"])
        self.path = self.root / self.prepared["ledgerPath"]
        self.ledger = helper._read_json(self.path)
        for order in self.ledger["orders"].values():
            self.mysql.orders[order["orderId"]] = {"owner": self.ledger["actors"][order["owner"]]["userId"],
                "number": order["orderNo"], "status": "RECEIVED", "amount": "39.80", "created": self.mysql.now}

    def native(self, argv, **kwargs):
        if argv[0] == self.env["MYSQL_EXE"]:
            if kwargs.get("input", "").strip() == "SELECT DATE_FORMAT(NOW(6), '%Y-%m-%dT%H:%i:%s.%f');":
                self.mysql.calls.append((argv, kwargs))
                return subprocess.CompletedProcess(argv, 0, self.mysql.now.isoformat(timespec="microseconds") + "\n", "")
            return self.mysql(argv, **kwargs)
        if str(argv[0]).endswith("java.exe") or str(argv[0]).endswith("/java"):
            from datetime import timedelta
            self.java_calls.append((argv, kwargs))
            clock = self.mysql.now + timedelta(seconds=self.clock_shift)
            return subprocess.CompletedProcess(argv, 0, json.dumps({"localDateTime": clock.isoformat(),
                "zoneId": "Asia/Shanghai", "instant": "2026-10-01T02:00:00Z"}), "")
        self.jvm_calls.append((argv, kwargs))
        if self.failure:
            if isinstance(self.failure, Exception):
                raise self.failure
            return subprocess.CompletedProcess(argv, self.failure, "private-id-9007199254740993", "db-secret")
        probe = kwargs["env"]["AFTER_SALES_EVAL_PROBE"]
        output = Path(kwargs["env"]["AFTER_SALES_EVAL_OUTPUT"])
        output.mkdir(parents=True, exist_ok=True)
        evidence = {"probe": probe, "durationMs": 12,
            "receiptClass": "COMPLETED" if probe == "CONCURRENT_IDEMPOTENCY" else "REJECTED",
            "assertionsPassed": True}
        if self.bad_evidence is not None:
            evidence = self.bad_evidence
        helper._atomic_json(output / "probe-result.json", evidence)
        return subprocess.CompletedProcess(argv, 0, "private-id-9007199254740993", "db-secret")

    def probe_request(self, probe="ROLLBACK_AFTER_INSERT"):
        return {"schemaVersion": 1, "op": "probe", "runId": "run-001", "caseId": "NORMAL-001",
            "trialId": self.ledger["trialId"], "ledgerPath": self.prepared["ledgerPath"], "probe": probe}

    def invoke(self, req=None, env=None):
        self.assertTrue(callable(getattr(helper, "probe", None)), "Task 4 probe helper is not implemented")
        return helper.probe(req or self.probe_request(), env if env is not None else self.env)

    def assert_error(self, result):
        self.assertEqual({"schemaVersion": 1, "op": "probe", "status": "ERROR", "errorCategory": "FIXTURE_ERROR"}, result)

    def test_probe_requires_explicit_flag_and_owned_ledger(self):
        for flag in (None, "false", "True", "1"):
            env = dict(self.env)
            env.pop("AFTER_SALES_EVAL_DB_TEST")
            if flag is not None:
                env["AFTER_SALES_EVAL_DB_TEST"] = flag
            before = len(self.mysql.calls)
            self.assert_error(self.invoke(env=env))
            self.assertEqual(before, len(self.mysql.calls))
        for change in ({"runId": "other-run"}, {"ledgerPath": "../ledger.json"},
                       {"ledgerPath": "run-001/NORMAL-001/other-trial/ledger.json"}, {"command": "rm anything"}):
            self.assert_error(self.invoke(dict(self.probe_request(), **change)))
        self.assertEqual([], self.jvm_calls)
        self.assertEqual([], self.java_calls)

    def test_successful_creation_journal_and_complete_ledger_required(self):
        for mutate in (lambda x: x.update(status="PREPARED"), lambda x: x["operations"][0].update(state="UNKNOWN"),
                       lambda x: x["orders"]["order-a"].update(orderId="9007199254999999"),
                       lambda x: x["orders"]["order-a"].update(owner="foreign"),
                       lambda x: x["orders"]["order-a"].pop("preparedStatus")):
            ledger = copy.deepcopy(self.ledger)
            mutate(ledger)
            helper._atomic_json(self.path, ledger)
            self.assert_error(self.invoke())
        self.assertEqual([], self.jvm_calls)

    def test_only_fixed_method_is_dispatched_and_evidence_is_closed(self):
        result = self.invoke()
        self.assertEqual({"schemaVersion", "op", "status", "probeEvidence"}, set(result))
        self.assertEqual("COMPLETED", result["status"])
        self.assertEqual({"probe", "durationMs", "receiptClass", "assertionsPassed"}, set(result["probeEvidence"]))
        argv, opts = self.jvm_calls[0]
        self.assertEqual([self.env["AFTER_SALES_EVAL_MAVEN_EXE"], "-pl", "mall-server", "-am",
            "-Dtest=AfterSalesDatabaseEvaluationIT#rollbackAfterInsert", "-Dsurefire.failIfNoSpecifiedTests=false", "test"], argv)
        self.assertFalse(opts["shell"])
        self.assertTrue(opts["capture_output"])
        self.assertEqual("backend-signing-secret", opts["env"]["MERCHANT_JWT_SECRET"])
        for key in ("MODEL_API_KEY", "DEMO_MERCHANT_TOKEN", "DEMO_MERCHANT_USERNAME", "DEMO_MERCHANT_PASSWORD"):
            self.assertNotIn(key, opts["env"])
        self.assertNotEqual(("3", "4"), (opts["env"]["MALL_WORKER_ID"], opts["env"]["MALL_DATACENTER_ID"]))
        self.assertEqual(str(self.path.resolve()), opts["env"]["AFTER_SALES_EVAL_LEDGER"])
        self.assertNotIn("9007199254740993", json.dumps(result))
        self.assertNotIn("db-secret", json.dumps(result))

    def test_unknown_probe_cannot_inject_method_or_native_argument(self):
        for probe in ("OTHER", "ROLLBACK_AFTER_INSERT#anything", "rollbackAfterInsert;whoami"):
            self.assert_error(self.invoke(dict(self.probe_request(), probe=probe)))
        self.assertEqual([], self.jvm_calls)

    def test_actual_unique_index_failure_prevents_jvm(self):
        self.mysql.indexes = [["wrong", "0", "1", "order_id", "NULL"], ["wrong", "0", "2", "status", "NULL"]]
        self.assert_error(self.invoke())
        self.assertEqual([], self.jvm_calls)
        self.assertEqual([], self.java_calls)

    def test_clock_mismatch_prevents_probe_before_any_write(self):
        self.clock_shift = 8 * 3600
        self.assert_error(self.invoke())
        self.assertEqual(1, len(self.java_calls))
        self.assertEqual([], self.jvm_calls)
        self.assertEqual(0, self.mysql.update_count)

    def test_failure_timeout_and_result_leaks_are_sanitized(self):
        for n, failure in enumerate((9, subprocess.TimeoutExpired("private-db-secret", 300), OSError("private-user-id")), 1):
            with self.subTest(failure=type(failure).__name__):
                self.failure = failure
                self.assert_error(self.invoke())
                # First failure remains; next failure uses a genuinely new fake trial.
                self.new_fixture("failure-next-" + str(n))
        self.failure = None
        self.bad_evidence = {"probe": "ROLLBACK_AFTER_INSERT", "durationMs": 12, "receiptClass": "REJECTED",
            "assertionsPassed": True, "rawId": "9007199254740993"}
        self.assert_error(self.invoke())

    def test_repeat_probe_is_not_automatically_executed(self):
        self.assertEqual("COMPLETED", self.invoke()["status"])
        self.assert_error(self.invoke())
        self.assertEqual(1, len(self.jvm_calls))

    def test_trial_cannot_switch_to_another_probe_after_first_execution(self):
        self.assertEqual("COMPLETED", self.invoke()["status"])
        self.assert_error(self.invoke(self.probe_request("STALE_POLICY")))
        self.assertEqual(1, len(self.jvm_calls))

    def test_ndjson_dispatch_returns_only_probe_evidence(self):
        output = io.StringIO()
        helper.serve(io.StringIO(json.dumps(self.probe_request()) + "\n"), output, self.env)
        result = json.loads(output.getvalue())
        self.assertEqual("COMPLETED", result["status"])
        self.assertEqual({"schemaVersion", "op", "status", "probeEvidence"}, set(result))


if __name__ == "__main__":
    unittest.main()
