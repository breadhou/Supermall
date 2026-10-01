"""Offline tests of the helper against a stateful fake business HTTP API."""

import importlib
import io
import json
import tempfile
import types
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit

import after_sales_eval_fixture as helper


def request(trial="trial-001", status="RECEIVED", refund="NONE"):
    return {"schemaVersion": 1, "op": "prepare", "runId": "run-001",
            "caseId": "NORMAL-001", "trialId": trial, "fixture": {
                "activeActor": "actor-a", "actors": ["actor-a"],
                "products": {"product-a": {"source": "RUN_MUTABLE", "name": "Evaluation paper",
                    "description": "Isolated fictional evaluation fixture", "skus": [
                        {"skuAlias": "sku-a", "price": "19.90", "stock": 10,
                         "specs": {"size": "A5"}}]}},
                "orders": {"order-a": {"owner": "actor-a", "status": status,
                    "ageSeconds": 0, "items": [{"skuAlias": "sku-a", "quantity": 2}],
                    "expectedPaidAmount": "39.800", "existingRefund": refund}}}}


class Response(io.BytesIO):
    status = 200

    def __init__(self, data, code=0):
        super().__init__(json.dumps({"status": "SUCCESS" if code == 0 else "ERROR",
                                    "code": code, "data": data}).encode())


class FakeBusinessApi:
    """Enforce ownership and state transitions instead of returning canned success."""

    def __init__(self, root):
        self.root = root
        self.calls = []
        self.users = {}
        self.addresses = {}
        self.skus = {}
        self.orders = {}
        self.products = {}
        self.refunds = {}
        self.next_id = 9007199254740993
        self.fail_path = None
        self.failure = None
        self.business_error = None
        self.bad_shape_path = None
        self.before_send = None

    def new_id(self):
        value = str(self.next_id)
        self.next_id += 1
        return value

    def __call__(self, req, timeout):
        path = urlsplit(req.full_url).path
        method = req.get_method()
        body = json.loads(req.data) if req.data else None
        token = req.get_header("Authorization", "").removeprefix("Bearer ")
        self.calls.append((method, path, body, token))
        if method != "GET" and path != "/api/merchant/login":
            ledgers = list(self.root.glob("*/*/*/ledger.json"))
            assert any(json.loads(p.read_text())["operations"][-1]["state"] == "PREPARED"
                       for p in ledgers), "write sent before durable journal"
        if self.before_send:
            self.before_send(method, path, body)
        if self.business_error == path:
            return Response(None, code=50001)
        data = self.handle(method, path, body, token)
        if self.fail_path == path:
            raise self.failure or TimeoutError("secret receipt lost")
        if self.bad_shape_path == path:
            data = {"private": "sensitive malformed receipt"}
        return Response(data)

    def handle(self, method, path, body, token):
        if path == "/api/merchant/login":
            assert set(body) == {"username", "password"}
            return {"accessToken": "merchant-secret"}
        if path == "/api/categories":
            assert token in self.users
            return [{"id": 7, "children": [{"id": 8, "children": []}]}]
        if path == "/api/merchant/orders" and method == "GET":
            assert token == "merchant-secret"
            return {"records": []}
        if path == "/api/auth/register":
            assert set(body) == {"username", "password", "phone"}
            assert body["username"] not in [u["username"] for u in self.users.values()]
            uid = self.new_id()
            user = {"id": uid, "username": body["username"], "accessToken": "user-secret-" + uid}
            self.users[user["accessToken"]] = user
            return user
        if path == "/api/user/address":
            assert token in self.users
            assert set(body) == {"receiver", "phone", "province", "city", "district", "detail", "isDefault"}
            assert type(body["isDefault"]) is int
            aid = self.new_id()
            self.addresses[aid] = token
            return {"id": aid}
        if path == "/api/merchant/products":
            assert token == "merchant-secret"
            assert set(body) == {"name", "description", "categoryId", "status", "skus"}
            assert isinstance(body["skus"][0]["specs"], str)
            pid = self.new_id()
            skus = []
            for sku in body["skus"]:
                sid = self.new_id()
                entry = dict(sku, id=sid)
                self.skus[sid] = Decimal(sku["price"])
                skus.append(entry)
            product = dict(body, id=pid, skus=skus)
            self.products[pid] = product
            return product
        if path.startswith("/api/products/") and method == "GET":
            assert token in self.users
            return self.products[path.rsplit("/", 1)[1]]
        if path == "/api/orders":
            assert set(body) == {"addressId", "items"}
            assert self.addresses[str(body["addressId"])] == token
            oid = self.new_id()
            amount = sum((self.skus[str(i["skuId"])] * i["quantity"] for i in body["items"]), Decimal(0))
            order = {"id": oid, "orderNo": "NO-" + oid, "userId": self.users[token]["id"],
                     "totalAmount": str(amount), "status": "PENDING", "token": token}
            self.orders[oid] = order
            return order
        if path.startswith("/api/merchant/orders/"):
            assert token == "merchant-secret"
            order_no, action = path.split("/")[-2:]
            order = next(o for o in self.orders.values() if o["orderNo"] == order_no)
            assert order["status"] == {"ship": "PAID", "deliver": "SHIPPED"}[action]
            order["status"] = {"ship": "SHIPPED", "deliver": "DELIVERED"}[action]
            if action == "ship":
                assert set(body) == {"company", "trackingNo"}
            return {"id": self.new_id(), "orderId": order["id"], "status": order["status"]}
        if path.startswith("/api/orders/"):
            oid = path.split("/")[3]
            order = self.orders[oid]
            assert order["token"] == token
            action = "/".join(path.split("/")[4:])
            if method == "GET":
                assert not action, "eligibility must not supply golden amount"
                return order
            if action == "pay":
                assert order["status"] == "PENDING"
                order["status"] = "PAID"
                return {"orderId": oid, "status": "SUCCESS", "amount": order["totalAmount"]}
            if action == "receive":
                assert method == "PUT" and order["status"] == "DELIVERED"
                order["status"] = "RECEIVED"
                return None
            if action == "cancel":
                assert method == "PUT" and order["status"] == "PENDING"
                order["status"] = "CANCELLED"
                return None
            if action == "refund":
                assert order["status"] in ("PAID", "DELIVERED", "RECEIVED")
                self.refunds[oid] = "PENDING"
                return None
            if action == "refund/execute":
                assert order["status"] in ("PAID", "RECEIVED")
                self.refunds[oid] = "REFUNDED"
                order["status"] = "REFUNDED"
                return {"orderId": oid, "eligible": True, "refundExists": False,
                        "reason": None, "refundableAmount": order["totalAmount"]}
        raise AssertionError("unexpected fixed endpoint")


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.environment = {"SUPERMALL_BASE_URL": "http://fixture.invalid",
            "DEMO_MERCHANT_TOKEN": "merchant-secret", "AFTER_SALES_EVAL_OUTPUT_ROOT": str(self.root)}
        self.api = FakeBusinessApi(self.root)
        self.opener = patch.object(helper, "urlopen", self.api)
        self.opener.start()
        self.addCleanup(self.opener.stop)

    def ledger(self, reply):
        return json.loads((self.root / reply["ledgerPath"]).read_text())

    def bindings(self, reply):
        return json.loads((self.root / reply["bindingFile"]).read_text())

    def test_fixture_uses_business_state_transitions(self):
        reply = helper.prepare(request(), self.environment)
        self.assertEqual("COMPLETED", reply["status"])
        writes = [(m, p) for m, p, _, _ in self.api.calls if m != "GET"]
        oid = next(iter(self.api.orders))
        no = self.api.orders[oid]["orderNo"]
        self.assertEqual([("POST", "/api/auth/register"), ("POST", "/api/user/address"),
            ("POST", "/api/merchant/products"), ("POST", "/api/orders"),
            ("POST", f"/api/orders/{oid}/pay"), ("POST", f"/api/merchant/orders/{no}/ship"),
            ("POST", f"/api/merchant/orders/{no}/deliver"), ("PUT", f"/api/orders/{oid}/receive")], writes)
        self.assertEqual("RECEIVED", self.api.orders[oid]["status"])
        self.assertTrue(all(op["state"] == "COMPLETED" for op in self.ledger(reply)["operations"]))
        self.assertEqual("39.80", self.ledger(reply)["orders"]["order-a"]["expectedPaidAmount"])
        self.assertEqual(oid, self.bindings(reply)["orders"]["order-a"]["orderId"])

    def test_category_read_uses_active_customer_authentication(self):
        req = request()
        req["fixture"]["actors"].append("actor-b")
        reply = helper.prepare(req, self.environment)
        token = self.bindings(reply)["actors"]["actor-a"]["userToken"]
        self.assertEqual([token], [t for _, p, _, t in self.api.calls if p == "/api/categories"])

    def test_completed_ledger_identity_cannot_be_rebound_to_another_trial(self):
        reply = helper.prepare(request(), self.environment)
        ledger = self.ledger(reply)
        ledger["trialId"] = "trial-another"
        (self.root / reply["ledgerPath"]).write_text(json.dumps(ledger))
        count = len(self.api.calls)
        self.assertEqual("ERROR", helper.prepare(request(), self.environment)["status"])
        self.assertEqual(count, len(self.api.calls))

    def test_creation_timeout_is_journaled_and_not_retried(self):
        self.api.fail_path = "/api/orders"
        reply = helper.prepare(request(), self.environment)
        self.assertEqual("UNKNOWN", reply["status"])
        self.assertEqual("FIXTURE_CREATION_UNKNOWN", reply["errorCategory"])
        ledger = self.ledger(reply)
        self.assertEqual("UNKNOWN", ledger["status"])
        self.assertEqual("UNKNOWN", ledger["operations"][-1]["state"])
        importlib.reload(helper)
        with patch.object(helper, "urlopen", self.api):
            again = helper.prepare(request(), self.environment)
        self.assertEqual(reply, again)
        self.assertEqual(1, sum(p == "/api/orders" for _, p, _, _ in self.api.calls))

    def test_reply_contains_no_credentials_or_raw_ids(self):
        reply = helper.prepare(request(), self.environment)
        self.assertEqual({"schemaVersion", "op", "status", "ledgerPath", "bindingFile"}, set(reply))
        public = json.dumps(reply)
        for secret in ("merchant-secret", "user-secret", "900719925474", "accessToken", "password"):
            self.assertNotIn(secret, public)
        bindings = self.bindings(reply)
        self.assertEqual({"userId", "userToken"}, set(bindings["actors"]["actor-a"]))
        self.assertNotIn("operations", bindings)

    def test_every_trial_gets_fresh_users_products_and_orders(self):
        first = self.bindings(helper.prepare(request(), self.environment))
        second = self.bindings(helper.prepare(request("trial-002"), self.environment))
        for group, alias, field in (("actors", "actor-a", "userId"),
                                   ("orders", "order-a", "orderId"),
                                   ("products", "product-a", "productId")):
            self.assertNotEqual(first[group][alias][field], second[group][alias][field])

    def test_cross_user_orders_keep_foreign_token_only_in_helper_ledger(self):
        req = request()
        req["fixture"]["actors"].append("actor-b")
        req["fixture"]["orders"]["order-a"]["owner"] = "actor-b"
        reply = helper.prepare(req, self.environment)
        binding = self.bindings(reply)
        ledger = self.ledger(reply)
        self.assertEqual({"userId"}, set(binding["actors"]["actor-b"]))
        self.assertIn("userToken", ledger["actors"]["actor-b"])
        self.assertEqual(binding["actors"]["actor-b"]["userId"], next(iter(self.api.orders.values()))["userId"])

    def test_each_reachable_order_status_and_legacy_pending(self):
        for status in ("PENDING", "PAID", "SHIPPED", "DELIVERED", "RECEIVED", "CANCELLED", "REFUNDED"):
            with self.subTest(status=status):
                refund = "REFUNDED" if status == "REFUNDED" else "NONE"
                reply = helper.prepare(request("trial-" + status, status, refund), self.environment)
                self.assertEqual("COMPLETED", reply["status"])
                oid = self.bindings(reply)["orders"]["order-a"]["orderId"]
                self.assertEqual(status, self.api.orders[oid]["status"])
        reply = helper.prepare(request("trial-legacy", "RECEIVED", "PENDING"), self.environment)
        oid = self.bindings(reply)["orders"]["order-a"]["orderId"]
        self.assertEqual("PENDING", self.api.refunds[oid])
        self.assertEqual("RECEIVED", self.api.orders[oid]["status"])

    def test_completed_refund_uses_execute_endpoint(self):
        reply = helper.prepare(request(refund="REFUNDED", status="REFUNDED"), self.environment)
        self.assertEqual("COMPLETED", reply["status"])
        self.assertTrue(any(p.endswith("/refund/execute") for _, p, _, _ in self.api.calls))
        self.assertFalse(any(p.endswith("/refund-eligibility") for _, p, _, _ in self.api.calls))

    def test_business_failure_does_not_count_as_completed_or_retry(self):
        self.api.business_error = "/api/orders"
        reply = helper.prepare(request(), self.environment)
        self.assertEqual("ERROR", reply["status"])
        again = helper.prepare(request(), self.environment)
        self.assertEqual(reply, again)
        self.assertEqual(1, sum(p == "/api/orders" for _, p, _, _ in self.api.calls))
        ledger = json.loads(next(self.root.glob("*/*/*/ledger.json")).read_text())
        self.assertNotEqual("COMPLETED", ledger["operations"][-1]["state"])
        self.assertEqual(50001, ledger["operations"][-1]["receipt"]["code"])

    def test_malformed_creation_receipt_is_unknown(self):
        self.api.bad_shape_path = "/api/orders"
        reply = helper.prepare(request(), self.environment)
        self.assertEqual("UNKNOWN", reply["status"])
        self.assertEqual("UNKNOWN", self.ledger(reply)["operations"][-1]["state"])

    def test_uncertain_transition_is_not_retried(self):
        self.api.fail_path = "/api/orders/9007199254740997/pay"
        reply = helper.prepare(request(), self.environment)
        self.assertEqual("UNKNOWN", reply["status"])
        self.assertEqual(reply, helper.prepare(request(), self.environment))
        self.assertEqual(1, sum(p.endswith("/pay") for _, p, _, _ in self.api.calls))

    def test_restart_with_prepared_action_is_not_sent_again(self):
        def interrupt(method, path, body):
            if path == "/api/orders":
                raise KeyboardInterrupt()
        self.api.before_send = interrupt
        with self.assertRaises(KeyboardInterrupt):
            helper.prepare(request(), self.environment)
        self.api.before_send = None
        reply = helper.prepare(request(), self.environment)
        self.assertEqual("UNKNOWN", reply["status"])
        self.assertEqual(1, sum(p == "/api/orders" for _, p, _, _ in self.api.calls))

    def test_completed_same_trial_is_read_only_and_changed_fixture_rejected(self):
        reply = helper.prepare(request(), self.environment)
        count = len(self.api.calls)
        self.assertEqual(reply, helper.prepare(request(), self.environment))
        changed = request()
        changed["fixture"]["products"]["product-a"]["name"] = "Changed"
        self.assertEqual("ERROR", helper.prepare(changed, self.environment)["status"])
        self.assertEqual(count, len(self.api.calls))

    def test_invalid_closed_requests_are_rejected_before_side_effects(self):
        bad_requests = []
        for field, value in (("runId", "../escape"), ("runId", ".."), ("trialId", "CON"),
                             ("caseId", "lowercase"), ("schemaVersion", True)):
            bad = request()
            bad[field] = value
            bad_requests.append(bad)
        bad = request()
        bad["token"] = "untrusted-secret"
        bad_requests.append(bad)
        bad = request()
        bad["fixture"] = {}
        bad_requests.append(bad)
        bad = request()
        bad["fixture"]["orders"]["order-a"]["expectedPaidAmount"] = "39.81"
        bad_requests.append(bad)
        bad = request()
        bad["fixture"]["products"]["product-a"]["skus"][0]["price"] = "NaN"
        bad_requests.append(bad)
        bad = request()
        bad["fixture"]["orders"]["order-a"]["items"][0]["quantity"] = True
        bad_requests.append(bad)
        for bad in bad_requests:
            self.assertEqual("ERROR", helper.prepare(bad, self.environment)["status"])
        self.assertEqual([], self.api.calls)

    def test_impossible_refund_combination_is_rejected_before_creation(self):
        for status, refund in (("PENDING", "PENDING"), ("SHIPPED", "PENDING"),
                               ("CANCELLED", "REFUNDED"), ("RECEIVED", "REFUNDED"), ("REFUNDED", "NONE")):
            self.assertEqual("ERROR", helper.prepare(request(status=status, refund=refund), self.environment)["status"])
        self.assertEqual([], self.api.calls)

    def test_age_hook_receives_owned_ledger_order_alias_and_seconds(self):
        req = request()
        req["fixture"]["orders"]["order-a"]["ageSeconds"] = 864000
        with patch.object(helper, "age_order") as age:
            reply = helper.prepare(req, self.environment)
        self.assertEqual("COMPLETED", reply["status"])
        age.assert_called_once_with(str(self.root / reply["ledgerPath"]), "order-a", 864000, self.environment)

    def test_missing_age_backend_rejects_before_business_creation(self):
        req = request()
        req["fixture"]["orders"]["order-a"]["ageSeconds"] = 864000
        with patch.object(helper, "_db_adapter", return_value=None):
            reply = helper.prepare(req, self.environment)
        self.assertEqual("ERROR", reply["status"])
        self.assertEqual([], self.api.calls)

    def test_database_adapter_hooks_use_ledger_paths_and_safe_oracle(self):
        adapter = types.ModuleType("after_sales_eval_db")
        calls = []
        def record_age(path, alias, seconds, env):
            calls.append((path, alias, seconds, env))
            ledger = json.loads(path.read_text())
            ledger["ageEvidence"] = {alias: {"backendTime": "2026-10-01T10:00:00", "ageSeconds": seconds}}
            path.write_text(json.dumps(ledger))
        adapter.age_order = record_age
        adapter.preflight = lambda env: {"ready": True}
        adapter.oracle = lambda path, terminal, env: {"orders": {"order-a": {
            "orderStatus": "RECEIVED", "paidAmount": "39.80", "refundRows": [], "ownerMatches": True}},
            "terminalEvidence": terminal}
        req = request()
        req["fixture"]["orders"]["order-a"]["ageSeconds"] = 864000
        with patch.dict("sys.modules", after_sales_eval_db=adapter):
            reply = helper.prepare(req, self.environment)
            self.assertEqual("COMPLETED", reply["status"])
            self.assertEqual([(self.root / reply["ledgerPath"], "order-a", 864000, self.environment)], calls)
            self.assertEqual({"order-a": {"backendTime": "2026-10-01T10:00:00", "ageSeconds": 864000}}, self.ledger(reply)["ageEvidence"])
            output = io.StringIO()
            wire = {k: v for k, v in req.items() if k != "fixture"}
            wire.update(op="oracle", ledgerPath=reply["ledgerPath"], terminalEvidence="UNKNOWN")
            helper.serve(io.StringIO(json.dumps(wire) + "\n"), output, self.environment)
            self.assertEqual({"schemaVersion": 1, "op": "oracle", "status": "COMPLETED",
                              "oracle": {"orders": {"order-a": {"orderStatus": "RECEIVED", "paidAmount": "39.80",
                                  "refundRows": [], "ownerMatches": True}}, "terminalEvidence": "UNKNOWN"}}, json.loads(output.getvalue()))

    def test_age_failure_preserves_adapter_private_evidence(self):
        adapter = types.ModuleType("after_sales_eval_db")
        adapter.preflight = lambda env: {"ready": True}
        def fail_age(path, alias, seconds, env):
            ledger = json.loads(path.read_text())
            ledger["ageEvidence"] = {alias: {"status": "ERROR", "errorCategory": "FIXTURE_ERROR",
                "backendTimeAfter": "2026-10-01T10:00:00"}}
            path.write_text(json.dumps(ledger))
            raise helper.FixtureError()
        adapter.age_order = fail_age
        req = request()
        req["fixture"]["orders"]["order-a"]["ageSeconds"] = 864000
        with patch.dict("sys.modules", after_sales_eval_db=adapter):
            reply = helper.prepare(req, self.environment)
        self.assertEqual("ERROR", reply["status"])
        ledger = json.loads((self.root / "run-001/NORMAL-001/trial-001/ledger.json").read_text())
        self.assertEqual("FIXTURE_ERROR", ledger["ageEvidence"]["order-a"]["errorCategory"])

    def test_age_database_readiness_failure_precedes_business_creation(self):
        adapter = types.ModuleType("after_sales_eval_db")
        adapter.preflight = lambda env: (_ for _ in ()).throw(helper.FixtureError())
        req = request()
        req["fixture"]["orders"]["order-a"]["ageSeconds"] = 864000
        with patch.dict("sys.modules", after_sales_eval_db=adapter):
            reply = helper.prepare(req, self.environment)
        self.assertEqual("ERROR", reply["status"])
        self.assertEqual([], self.api.calls)

    def test_adapter_oracle_requires_every_declared_order_alias(self):
        prepared = helper.prepare(request(), self.environment)
        adapter = types.ModuleType("after_sales_eval_db")
        adapter.oracle = lambda path, terminal, env: {"orders": {}, "terminalEvidence": terminal}
        with patch.dict("sys.modules", after_sales_eval_db=adapter):
            with self.assertRaises(helper.FixtureError):
                helper.oracle(self.root / prepared["ledgerPath"], "UNKNOWN", self.environment)

    def test_adapter_oracle_cannot_export_raw_ids_or_unknown_fields(self):
        prepared = helper.prepare(request(), self.environment)
        adapter = types.ModuleType("after_sales_eval_db")
        adapter.oracle = lambda path, terminal, env: {"orders": {}, "terminalEvidence": terminal,
                                                     "rawId": "private-9007199254740993"}
        req = {k: v for k, v in request().items() if k != "fixture"}
        req.update(op="oracle", ledgerPath=prepared["ledgerPath"], terminalEvidence="UNKNOWN")
        output = io.StringIO()
        with patch.dict("sys.modules", after_sales_eval_db=adapter):
            helper.serve(io.StringIO(json.dumps(req) + "\n"), output, self.environment)
        self.assertEqual({"schemaVersion": 1, "op": "oracle", "status": "ERROR",
                          "errorCategory": "FIXTURE_ERROR"}, json.loads(output.getvalue()))
        self.assertNotIn("private-", output.getvalue())

    def test_multiple_orders_and_reordered_skus_preserve_bindings_and_amounts(self):
        req = request()
        fixture = req["fixture"]
        fixture["actors"].append("actor-b")
        fixture["products"]["product-a"]["skus"].append(
            {"skuAlias": "sku-b", "price": "0.10", "stock": 100, "specs": {"size": "A6"}})
        fixture["orders"]["order-a"]["items"] = [{"skuAlias": "sku-a", "quantity": 1}, {"skuAlias": "sku-b", "quantity": 3}]
        fixture["orders"]["order-a"]["expectedPaidAmount"] = "20.2000"
        fixture["orders"]["order-b"] = {"owner": "actor-b", "status": "PAID", "ageSeconds": 0,
            "items": [{"skuAlias": "sku-b", "quantity": 7}], "expectedPaidAmount": "0.70", "existingRefund": "NONE"}
        original = self.api.handle
        def reversed_skus(method, path, body, token):
            data = original(method, path, body, token)
            if path == "/api/merchant/products":
                data["skus"].reverse()
            return data
        self.api.handle = reversed_skus
        reply = helper.prepare(req, self.environment)
        self.assertEqual("COMPLETED", reply["status"])
        binding = self.bindings(reply)
        self.assertEqual({"order-a", "order-b"}, set(binding["orders"]))
        for alias, expected, status in (("order-a", Decimal("20.20"), "RECEIVED"), ("order-b", Decimal("0.70"), "PAID")):
            actual = self.api.orders[binding["orders"][alias]["orderId"]]
            self.assertEqual(expected, Decimal(actual["totalAmount"]))
            self.assertEqual(status, actual["status"])
        self.assertEqual("0.10", binding["products"]["product-a"]["skus"]["sku-b"]["price"])

    def test_identical_mutable_sku_values_still_bind_distinct_aliases(self):
        req = request(status="PENDING")
        req["fixture"]["products"]["product-a"]["skus"].append(
            {"skuAlias": "sku-b", "price": "19.90", "stock": 20, "specs": {"size": "A5"}})
        req["fixture"]["orders"]["order-a"]["items"].append({"skuAlias": "sku-b", "quantity": 1})
        req["fixture"]["orders"]["order-a"]["expectedPaidAmount"] = "59.70"
        reply = helper.prepare(req, self.environment)
        self.assertEqual("COMPLETED", reply["status"])
        skus = self.bindings(reply)["products"]["product-a"]["skus"]
        self.assertNotEqual(skus["sku-a"]["skuId"], skus["sku-b"]["skuId"])

    def test_installed_adapter_import_error_is_not_silently_unsupported(self):
        with patch("importlib.import_module", side_effect=ModuleNotFoundError(name="adapter_internal_dependency")):
            with self.assertRaises(ModuleNotFoundError):
                helper._db_adapter()

    def test_merchant_login_uses_existing_normal_credentials_only(self):
        env = dict(self.environment)
        del env["DEMO_MERCHANT_TOKEN"]
        env.update(DEMO_MERCHANT_USERNAME="configured-merchant", DEMO_MERCHANT_PASSWORD="private-password")
        reply = helper.prepare(request(), env)
        self.assertEqual("COMPLETED", reply["status"])
        self.assertEqual(("POST", "/api/merchant/login", {"username": "configured-merchant", "password": "private-password"}), self.api.calls[0][:3])
        self.assertNotIn("private-password", json.dumps(reply))

    def test_preflight_missing_credentials_is_fixed_error(self):
        env = dict(self.environment)
        del env["DEMO_MERCHANT_TOKEN"]
        source = request()
        source.pop("fixture")
        source["op"] = "preflight"
        out = io.StringIO()
        helper.serve(io.StringIO(json.dumps(source) + "\n"), out, env)
        self.assertEqual({"schemaVersion": 1, "op": "preflight", "status": "ERROR", "errorCategory": "FIXTURE_ERROR"}, json.loads(out.getvalue()))
        self.assertEqual([], self.api.calls)

    def test_demo_products_only_read_and_price_is_catalog_derived(self):
        catalog = self.root / "catalog.json"
        manifest = self.root / "manifest.json"
        catalog.write_text(json.dumps([{"logicalKey": "paper_grid_a5", "name": "Demo", "description": "Fictional demo", "skus": [{"specs": "A5", "price": "18.80", "stock": 40}]}]))
        manifest.write_text(json.dumps({"paper_grid_a5": 9007199254741500}))
        pid, sid = "9007199254741500", "9007199254741501"
        self.api.products[pid] = {"id": pid, "status": "ON_SHELF", "name": "Demo", "description": "Fictional demo", "skus": [{"id": sid, "price": "18.800", "specs": "A5"}]}
        self.api.skus[sid] = Decimal("18.80")
        env = dict(self.environment, AFTER_SALES_EVAL_DEMO_CATALOG=str(catalog), AFTER_SALES_EVAL_DEMO_MANIFEST=str(manifest))
        req = request(status="PENDING")
        req["fixture"]["products"]["product-a"] = {"source": "DEMO_READONLY", "logicalKey": "paper_grid_a5"}
        order = req["fixture"]["orders"]["order-a"]
        order["items"][0]["skuAlias"] = "product-a-sku-01"
        order["expectedPaidAmount"] = "37.60"
        reply = helper.prepare(req, env)
        self.assertEqual("COMPLETED", reply["status"])
        self.assertEqual(sid, self.bindings(reply)["products"]["product-a"]["skus"]["product-a-sku-01"]["skuId"])
        self.assertFalse(any(p.startswith("/api/merchant/products") for _, p, _, _ in self.api.calls))
        self.assertTrue(any(m == "GET" and p == "/api/products/" + pid for m, p, _, _ in self.api.calls))
        token = self.bindings(reply)["actors"]["actor-a"]["userToken"]
        self.assertEqual([token], [t for _, p, _, t in self.api.calls if p == "/api/products/" + pid])

    def test_ndjson_is_closed_and_unsupported_operations_are_safe(self):
        prepare = request()
        other = {k: v for k, v in prepare.items() if k != "fixture"}
        commands = [dict(other, op="preflight"), prepare,
                    dict(other, op="oracle", ledgerPath="run-001/NORMAL-001/trial-001/ledger.json", terminalEvidence="UNKNOWN"),
                    dict(other, op="probe", ledgerPath="run-001/NORMAL-001/trial-001/ledger.json", probe="ROLLBACK_AFTER_INSERT"),
                    dict(other, op="oracle", ledgerPath="../private.env", terminalEvidence="NOT_SENT"),
                    dict(other, op="shutdown")]
        output = io.StringIO()
        with patch.object(helper, "_db_adapter", return_value=None):
            helper.serve(io.StringIO("\n".join(json.dumps(c) for c in commands + [prepare]) + "\n"), output, self.environment)
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(6, len(replies))
        self.assertEqual(["COMPLETED", "COMPLETED", "ERROR", "ERROR", "ERROR", "COMPLETED"], [r["status"] for r in replies])
        self.assertEqual("FIXTURE_ERROR", replies[2]["errorCategory"])
        self.assertNotIn("secret", output.getvalue())

    def test_ndjson_rejects_extra_fields_bad_json_and_unknown_operation(self):
        output = io.StringIO()
        helper.serve(io.StringIO('{"op":"shell","command":"arbitrary"}\n{bad json\n' + json.dumps(dict(request(), password="secret")) + "\n"), output, self.environment)
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(3, len(replies))
        self.assertTrue(all(r == {"schemaVersion": 1, "op": "preflight", "status": "ERROR", "errorCategory": "FIXTURE_ERROR"} or r == {"schemaVersion": 1, "op": "prepare", "status": "ERROR", "errorCategory": "FIXTURE_ERROR"} for r in replies))
        self.assertEqual([], self.api.calls)

    def test_external_ledger_paths_and_different_trial_ledger_are_rejected(self):
        prepared = helper.prepare(request(), self.environment)
        req = {k: v for k, v in request().items() if k != "fixture"}
        for path in ("../outside", "C:/outside", "run-001/NORMAL-001/trial-other/ledger.json", "run-001\\NORMAL-001\\trial-001\\ledger.json"):
            out = io.StringIO()
            helper.serve(io.StringIO(json.dumps(dict(req, op="oracle", ledgerPath=path, terminalEvidence="NOT_SENT")) + "\n"), out, self.environment)
            self.assertEqual("ERROR", json.loads(out.getvalue())["status"])

    def test_environment_file_loads_only_allowed_keys_without_expansion(self):
        path = self.root / "private.env"
        path.write_text('DEMO_MERCHANT_USERNAME="configured"\nDEMO_MERCHANT_PASSWORD=private-password\nMODEL_API_KEY=model-secret\nUNRELATED_SECRET=secret\nAFTER_SALES_EVAL_CATEGORY_ID=8\n')
        env = helper.load_environment(path, {"SUPERMALL_BASE_URL": "http://fixture.invalid", "MODEL_API_KEY": "inherited-secret"})
        self.assertEqual("private-password", env["DEMO_MERCHANT_PASSWORD"])
        self.assertNotIn("MODEL_API_KEY", env)
        self.assertNotIn("UNRELATED_SECRET", env)
        self.assertEqual("8", env["AFTER_SALES_EVAL_CATEGORY_ID"])

    def test_environment_loader_adds_only_database_and_native_runtime_keys(self):
        path = self.root / "private.env"
        path.write_text('SPRING_DATASOURCE_URL=jdbc:mysql://localhost/mall\nSPRING_DATASOURCE_USERNAME=root\n'
            'SPRING_DATASOURCE_PASSWORD=private-db-password\nMYSQL_EXE="D:/MySQL/MySQL Server 8.0/bin/mysql.exe"\n'
            'MERCHANT_JWT_SECRET=signing-secret\nMODEL_API_KEY=model-secret\nMYSQL_PWD=untrusted-other-password\n')
        env = helper.load_environment(path, {"PATH": "native-path", "SystemRoot": "C:/Windows"})
        self.assertEqual("private-db-password", env["SPRING_DATASOURCE_PASSWORD"])
        self.assertEqual("native-path", env["PATH"])
        self.assertEqual("C:/Windows", env["SystemRoot"])
        self.assertNotIn("MODEL_API_KEY", env)
        self.assertNotIn("MERCHANT_JWT_SECRET", env)
        self.assertNotIn("MYSQL_PWD", env)


if __name__ == "__main__":
    unittest.main()
