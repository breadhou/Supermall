"""Prepare isolated evaluation data through fixed supermall business APIs.

Private files live beneath the configured output root. A trial directory is
claimed once; incomplete or uncertain writes never trigger automatic recreation,
even after process restart. The helper does not sign merchant tokens or execute
SQL/shell. Database age/oracle use a separate restricted adapter; opt-in
transaction probes dispatch only fixed test methods with private evidence.
"""

import argparse
import hashlib
import importlib
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
from datetime import datetime
from decimal import Decimal
from pathlib import Path, PurePosixPath
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


SAFE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
CASE_ID = re.compile(r"[A-Z][A-Z0-9_-]{0,63}")
ALIAS = re.compile(r"[a-z][a-z0-9-]{0,63}")
LOGICAL_KEY = re.compile(r"[a-z][a-z0-9_]{0,63}")
MONEY = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]+)?")
OPS = {"prepare", "preflight", "oracle", "probe", "shutdown"}
STATUSES = {"PENDING", "PAID", "SHIPPED", "DELIVERED", "RECEIVED", "CANCELLED", "REFUNDED"}
PROBES = {"ROLLBACK_AFTER_INSERT", "CONCURRENT_IDEMPOTENCY", "LEGACY_PENDING", "STALE_POLICY", "POLICY_WINDOW_FIXED_TIME"}
ENV_KEYS = {"SUPERMALL_BASE_URL", "DEMO_MERCHANT_TOKEN", "DEMO_MERCHANT_USERNAME",
            "DEMO_MERCHANT_PASSWORD", "AFTER_SALES_EVAL_OUTPUT_ROOT",
            "AFTER_SALES_EVAL_CATEGORY_ID", "AFTER_SALES_EVAL_DEMO_CATALOG",
            "AFTER_SALES_EVAL_DEMO_MANIFEST", "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "MYSQL_EXE",
            "PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP",
            "AFTER_SALES_EVAL_DB_TEST", "AFTER_SALES_EVAL_MAVEN_EXE", "JAVA_HOME",
            "MERCHANT_JWT_SECRET", "MALL_WORKER_ID", "MALL_DATACENTER_ID",
            "SPRING_DATA_REDIS_HOST", "SPRING_DATA_REDIS_PORT", "SPRING_DATA_REDIS_PASSWORD",
            "SPRING_RABBITMQ_HOST", "SPRING_RABBITMQ_PORT", "SPRING_RABBITMQ_USERNAME",
            "SPRING_RABBITMQ_PASSWORD"}
PROBE_METHODS = {"ROLLBACK_AFTER_INSERT": "rollbackAfterInsert",
    "CONCURRENT_IDEMPOTENCY": "concurrentIdempotency",
    "LEGACY_PENDING": "legacyPendingDoesNotMeanRefunded", "STALE_POLICY": "stalePolicyDoesNotWrite",
    "POLICY_WINDOW_FIXED_TIME": "policyWindowFixedTime"}
JVM_KEYS = {"JAVA_HOME", "PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP",
    "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
    "MERCHANT_JWT_SECRET", "MALL_WORKER_ID", "MALL_DATACENTER_ID",
    "SPRING_DATA_REDIS_HOST", "SPRING_DATA_REDIS_PORT", "SPRING_DATA_REDIS_PASSWORD",
    "SPRING_RABBITMQ_HOST", "SPRING_RABBITMQ_PORT", "SPRING_RABBITMQ_USERNAME", "SPRING_RABBITMQ_PASSWORD"}


class FixtureError(Exception):
    """Internal fixed error; its text is never serialized to the wire."""


class BusinessError(FixtureError):
    def __init__(self, code, receipt):
        self.code = code
        self.receipt = receipt


class CreationUnknown(FixtureError):
    pass


def _object(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys):
        raise FixtureError()


def _text(value):
    if not isinstance(value, str) or not value.strip():
        raise FixtureError()
    return value


def _pattern(value, pattern):
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise FixtureError()
    return value


def _integer(value, minimum=0):
    if type(value) is not int or not minimum <= value <= 2147483647:
        raise FixtureError()
    return value


def _money(value):
    _pattern(value, MONEY)
    decimal = Decimal(value)
    if decimal <= 0:
        raise FixtureError()
    return decimal


def _decimal_id(value):
    if type(value) is int:
        value = str(value)
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]*", value):
        raise FixtureError()
    return value


def _path_segment(value, pattern):
    _pattern(value, pattern)
    # Windows device names and trailing dots can alias another on-disk trial.
    if (value.endswith(".") or value in (".", "..")
            or re.fullmatch(r"(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", value, re.I)):
        raise FixtureError()
    return value


def _scope(request, environment):
    root_value = environment.get("AFTER_SALES_EVAL_OUTPUT_ROOT")
    root = Path(root_value) if root_value else Path(__file__).resolve().parents[1] / "mall-server/target/after-sales-eval"
    root = root.resolve()
    parts = [_path_segment(request["runId"], SAFE_ID),
             _path_segment(request["caseId"], CASE_ID),
             _path_segment(request["trialId"], SAFE_ID)]
    trial = root.joinpath(*parts)
    if not trial.resolve().is_relative_to(root):
        raise FixtureError()
    return root, trial


def _relative_ledger(request, environment):
    value = request["ledgerPath"]
    if not isinstance(value, str) or "\\" in value or ":" in value:
        raise FixtureError()
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in (".", "..") for part in value.split("/")):
        raise FixtureError()
    root, trial = _scope(request, environment)
    expected = trial / "ledger.json"
    if path.as_posix() != expected.relative_to(root).as_posix() or not expected.resolve().is_relative_to(trial.resolve()):
        raise FixtureError()
    ledger = _read_json(expected)
    for field in ("runId", "caseId", "trialId"):
        if ledger.get(field) != request[field]:
            raise FixtureError()
    return expected


def _validate_request(request, environment):
    if not isinstance(request, dict) or request.get("op") not in OPS:
        raise FixtureError()
    payload = {"prepare": {"fixture"}, "oracle": {"ledgerPath", "terminalEvidence"},
               "probe": {"ledgerPath", "probe"}, "preflight": set(), "shutdown": set()}[request["op"]]
    _object(request, {"schemaVersion", "op", "runId", "caseId", "trialId"} | payload)
    if type(request["schemaVersion"]) is not int or request["schemaVersion"] != 1:
        raise FixtureError()
    _scope(request, environment)
    if request["op"] == "oracle":
        if request["terminalEvidence"] not in ("NOT_SENT", "COMPLETED", "UNKNOWN"):
            raise FixtureError()
        _relative_ledger(request, environment)
    elif request["op"] == "probe":
        if request["probe"] not in PROBES:
            raise FixtureError()
        _relative_ledger(request, environment)


def _read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def _demo_files(environment):
    catalog = _read_json(_text(environment.get("AFTER_SALES_EVAL_DEMO_CATALOG")))
    manifest = _read_json(_text(environment.get("AFTER_SALES_EVAL_DEMO_MANIFEST")))
    if not isinstance(catalog, list) or not isinstance(manifest, dict):
        raise FixtureError()
    indexed = {}
    for product in catalog:
        key = _pattern(product.get("logicalKey"), LOGICAL_KEY)
        if key in indexed:
            raise FixtureError()
        indexed[key] = product
    return indexed, manifest


def _validate_fixture(fixture, environment):
    _object(fixture, ("activeActor", "actors", "products", "orders"))
    actors = fixture["actors"]
    if not isinstance(actors, list) or not actors or len(set(actors)) != len(actors):
        raise FixtureError()
    for alias in actors:
        _pattern(alias, ALIAS)
    if fixture["activeActor"] not in actors:
        raise FixtureError()
    products, orders = fixture["products"], fixture["orders"]
    if not isinstance(products, dict) or not products or not isinstance(orders, dict) or not orders or products.keys() & orders.keys():
        raise FixtureError()
    prices, demo = {}, {}
    for alias, product in products.items():
        _pattern(alias, ALIAS)
        if not isinstance(product, dict):
            raise FixtureError()
        if product.get("source") == "RUN_MUTABLE":
            _object(product, ("source", "name", "description", "skus"))
            _text(product["name"])
            _text(product["description"])
            skus = product["skus"]
            if not isinstance(skus, list) or not skus:
                raise FixtureError()
            for sku in skus:
                _object(sku, ("skuAlias", "price", "stock", "specs"))
                sku_alias = _pattern(sku["skuAlias"], ALIAS)
                if sku_alias in prices:
                    raise FixtureError()
                prices[sku_alias] = _money(sku["price"])
                _integer(sku["stock"])
                if not isinstance(sku["specs"], dict):
                    raise FixtureError()
                for key, value in sku["specs"].items():
                    _text(key)
                    if not isinstance(value, str):
                        raise FixtureError()
        elif product.get("source") == "DEMO_READONLY":
            _object(product, ("source", "logicalKey"))
            catalog, manifest = _demo_files(environment)
            key = _pattern(product["logicalKey"], LOGICAL_KEY)
            definition = catalog.get(key)
            if not definition or key not in manifest:
                raise FixtureError()
            product_id = _decimal_id(manifest[key])
            if not isinstance(definition.get("skus"), list) or not definition["skus"]:
                raise FixtureError()
            demo[alias] = {"productId": product_id, "definition": definition}
            for index, sku in enumerate(definition["skus"], 1):
                sku_alias = _pattern(f"{alias}-sku-{index:02d}", ALIAS)
                if sku_alias in prices:
                    raise FixtureError()
                prices[sku_alias] = _money(sku["price"])
        else:
            raise FixtureError()
    amounts = {}
    for alias, order in orders.items():
        _pattern(alias, ALIAS)
        _object(order, ("owner", "status", "ageSeconds", "items", "expectedPaidAmount", "existingRefund"))
        if order["owner"] not in actors or order["status"] not in STATUSES:
            raise FixtureError()
        _integer(order["ageSeconds"])
        existing = order["existingRefund"]
        if (existing not in ("NONE", "PENDING", "REFUNDED")
                or (existing == "PENDING" and order["status"] not in ("PAID", "DELIVERED", "RECEIVED"))
                or ((existing == "REFUNDED") != (order["status"] == "REFUNDED"))):
            raise FixtureError()
        if not isinstance(order["items"], list) or not order["items"]:
            raise FixtureError()
        total = Decimal(0)
        for item in order["items"]:
            _object(item, ("skuAlias", "quantity"))
            if item["skuAlias"] not in prices:
                raise FixtureError()
            total += prices[item["skuAlias"]] * _integer(item["quantity"], 1)
        if total != _money(order["expectedPaidAmount"]):
            raise FixtureError()
        amounts[alias] = format(total, "f")
    return prices, amounts, demo


def _atomic_json(path, value):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".fixture-", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward fixture credentials to a redirect target or resend a write.
        return None


# The public name provides the ordinary urllib testing seam; production uses a
# redirect-disabled opener, with no automatic business/request retry loop.
urlopen = build_opener(_NoRedirect()).open


def _base_url(environment):
    value = environment.get("SUPERMALL_BASE_URL", "http://localhost:8081").rstrip("/")
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.netloc
            or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
        raise FixtureError()
    return value


def _http(environment, method, path, token=None, body=None):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(_base_url(environment) + path,
                  data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None,
                  headers=headers, method=method)
    with urlopen(req, timeout=30) as response:
        if response.status != 200:
            raise FixtureError()
        receipt = json.load(response, parse_float=Decimal)
    if not isinstance(receipt, dict) or type(receipt.get("code")) is not int:
        raise FixtureError()
    # JSON decimal lexemes remain exact in private receipts and assertions.
    receipt = json.loads(json.dumps(receipt, default=lambda x: format(x, "f")))
    if receipt["code"] != 0:
        raise BusinessError(receipt["code"], receipt)
    if receipt.get("status") != "SUCCESS" or "data" not in receipt:
        raise FixtureError()
    return receipt


def _merchant(environment):
    token = environment.get("DEMO_MERCHANT_TOKEN", "").strip()
    if token:
        if token.startswith("${"):
            raise FixtureError()
        return token
    username = _text(environment.get("DEMO_MERCHANT_USERNAME"))
    password = _text(environment.get("DEMO_MERCHANT_PASSWORD"))
    receipt = _http(environment, "POST", "/api/merchant/login",
                    body={"username": username, "password": password})
    return _text(receipt["data"].get("accessToken"))


def preflight(environment):
    """Check the configured merchant through the existing read API."""
    token = _merchant(environment)
    data = _http(environment, "GET", "/api/merchant/orders?pageNum=1&pageSize=1", token)["data"]
    if not isinstance(data, dict) or not isinstance(data.get("records"), list):
        raise FixtureError()
    adapter = _db_adapter()
    if adapter is not None:
        adapter.preflight(environment)
    if environment.get("AFTER_SALES_EVAL_DB_TEST") == "true":
        _clock_preflight(environment)


def _db_adapter():
    try:
        return importlib.import_module("after_sales_eval_db")
    except ModuleNotFoundError as error:
        if error.name == "after_sales_eval_db":
            return None
        raise


def age_order(ledgerPath, orderAlias, ageSeconds, environment):
    """Delegate fixture-owned age adjustment to the restricted private adapter."""
    adapter = _db_adapter()
    if adapter is None:
        raise FixtureError()
    adapter.age_order(Path(ledgerPath), orderAlias, ageSeconds, environment)


_DEFAULT_AGE_ORDER = age_order


def oracle(ledgerPath, terminalEvidence, environment):
    """Return a closed, complete projection without private business identity."""
    adapter = _db_adapter()
    if adapter is None:
        raise FixtureError()
    projection = adapter.oracle(Path(ledgerPath), terminalEvidence, environment)
    _object(projection, ("orders", "terminalEvidence"))
    if projection["terminalEvidence"] != terminalEvidence or not isinstance(projection["orders"], dict):
        raise FixtureError()
    declared = _read_json(ledgerPath)["orders"]
    if set(projection["orders"]) != set(declared):
        raise FixtureError()
    for alias, order in projection["orders"].items():
        if alias not in declared:
            raise FixtureError()
        _object(order, ("orderStatus", "paidAmount", "refundRows", "ownerMatches"))
        if order["orderStatus"] not in STATUSES or type(order["ownerMatches"]) is not bool:
            raise FixtureError()
        _pattern(order["paidAmount"], MONEY)
        if not isinstance(order["refundRows"], list):
            raise FixtureError()
        for row in order["refundRows"]:
            _object(row, ("status", "amount", "ownerMatches"))
            if row["status"] not in ("NONE", "PENDING", "REFUNDED") or type(row["ownerMatches"]) is not bool:
                raise FixtureError()
            _pattern(row["amount"], MONEY)
    return projection


def _category(environment, customer_token):
    configured = environment.get("AFTER_SALES_EVAL_CATEGORY_ID")
    if configured:
        return _decimal_id(configured)
    tree = _http(environment, "GET", "/api/categories", customer_token)["data"]
    if not isinstance(tree, list) or not tree:
        raise FixtureError()
    def leaf(nodes):
        for node in nodes:
            if not isinstance(node, dict):
                raise FixtureError()
            children = node.get("children")
            if children:
                found = leaf(children)
                if found:
                    return found
            else:
                return _decimal_id(node["id"])
        raise FixtureError()
    return leaf(tree)


def _reply(op, status="COMPLETED", **payload):
    return {"schemaVersion": 1, "op": op, "status": status, **payload}


def _error(op):
    return _reply(op, "ERROR", errorCategory="FIXTURE_ERROR")


def _java_executable(environment):
    return str(Path(_text(environment.get("JAVA_HOME"))) / "bin" / ("java.exe" if os.name == "nt" else "java"))


def _clock_preflight(environment):
    """Measure the evaluator's actual LocalDateTime clock against DB NOW(6).

    This standard-library Java source uses the same clock as the actual
    RefundEligibilityEvaluator. It starts no Spring context and performs no
    writes. Its measurements and native output stay in the helper domain.
    """
    if environment.get("AFTER_SALES_EVAL_DB_TEST") != "true":
        raise FixtureError()
    adapter = _db_adapter()
    if adapter is None:
        raise FixtureError()
    client = adapter._Mysql(environment)
    client.readiness()
    root, _ = _scope({"runId": "clock", "caseId": "DEV-CLOCK", "trialId": "measurement"}, environment)
    root.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="clock-gate-", dir=root))
    source = directory / "EvaluationClock.java"
    source.write_text('import java.time.*;\nclass EvaluationClock { public static void main(String[] args) {\n'
        'System.out.print("{\\"localDateTime\\":\\"" + LocalDateTime.now() + "\\",\\"zoneId\\":\\""'
        ' + ZoneId.systemDefault() + "\\",\\"instant\\":\\"" + Instant.now() + "\\"}"); }}\n', encoding="utf-8")
    sql = "SELECT DATE_FORMAT(NOW(6), '%Y-%m-%dT%H:%i:%s.%f');"
    evidence = {"status": "PREPARED"}
    _atomic_json(directory / "clock-evidence.json", evidence)
    try:
        host_before = datetime.now()
        before = client.query(sql, 1)
        result = subprocess.run([_java_executable(environment), "--source", "17", str(source)],
            shell=False, capture_output=True, text=True, encoding="utf-8", timeout=30,
            env={k: v for k, v in environment.items() if k in {
                "JAVA_HOME", "PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP"}})
        (directory / "jvm.stdout").write_text(result.stdout, encoding="utf-8")
        (directory / "jvm.stderr").write_text(result.stderr, encoding="utf-8")
        after = client.query(sql, 1)
        host_after = datetime.now()
        clock = json.loads(result.stdout)
        _object(clock, ("localDateTime", "zoneId", "instant"))
        if result.returncode != 0 or len(before) != 1 or len(after) != 1:
            raise FixtureError()
        start, end, jvm = adapter._time(before[0][0]), adapter._time(after[0][0]), adapter._time(clock["localDateTime"])
        elapsed = (end - start).total_seconds()
        lower, upper = (jvm - start).total_seconds(), (jvm - end).total_seconds()
        evidence.update(dbBefore=before[0][0], dbAfter=after[0][0], jvm=clock,
            hostBefore=host_before.isoformat(), hostAfter=host_after.isoformat(),
            intervalSeconds=elapsed, jvmOffsetLowerSeconds=lower, jvmOffsetUpperSeconds=upper)
        if elapsed < 0 or elapsed > 10 or lower < -2 or upper > 2:
            raise FixtureError()
        evidence["status"] = "COMPLETED"
        _atomic_json(directory / "clock-evidence.json", evidence)
        return evidence
    except Exception:
        evidence.update(status="ERROR", errorCategory="FIXTURE_ERROR")
        _atomic_json(directory / "clock-evidence.json", evidence)
        raise FixtureError() from None


def _probe_environment(environment, ledger_path, output, name):
    child = {k: v for k, v in environment.items() if k in JVM_KEYS}
    _text(child.get("MERCHANT_JWT_SECRET"))
    # The running backend uses the configured tuple. The sequential test JVM
    # always chooses its next tuple; never blindly reuse the live instance ID.
    worker = int(_pattern(child.get("MALL_WORKER_ID"), re.compile(r"(?:[0-9]|[12][0-9]|3[01])")))
    center = int(_pattern(child.get("MALL_DATACENTER_ID"), re.compile(r"(?:[0-9]|[12][0-9]|3[01])")))
    selected = (center * 32 + worker + 1) % 1024
    child.update(MALL_WORKER_ID=str(selected % 32), MALL_DATACENTER_ID=str(selected // 32),
        AFTER_SALES_EVAL_DB_TEST="true", AFTER_SALES_EVAL_LEDGER=str(ledger_path.resolve()),
        AFTER_SALES_EVAL_OUTPUT=str(output.resolve()), AFTER_SALES_EVAL_PROBE=name)
    return child


def probe(request: dict, environment: dict[str, str]) -> dict:
    """One explicitly enabled, ledger-owned fixed Java probe; no automatic retry."""
    output = None
    try:
        # Check before even loading the native adapter: default tests touch no DB/JVM.
        if environment.get("AFTER_SALES_EVAL_DB_TEST") != "true":
            raise FixtureError()
        _validate_request(request, environment)
        if request["op"] != "probe":
            raise FixtureError()
        adapter = _db_adapter()
        if adapter is None:
            raise FixtureError()
        path, ledger = adapter._load_ledger(_relative_ledger(request, environment), environment)
        _validate_fixture(ledger["fixture"], environment)
        if (ledger["status"] != "COMPLETED" or len(ledger["orders"]) != 1
                or set(ledger["orders"]) != set(ledger["fixture"]["orders"])
                or any(entry.get("state") != "COMPLETED" for entry in ledger["operations"])
                or any(p["source"] != "RUN_MUTABLE" for p in ledger["fixture"]["products"].values())):
            raise FixtureError()
        order = next(iter(ledger["orders"].values()))
        existing = "PENDING" if request["probe"] == "LEGACY_PENDING" else "NONE"
        if (order["owner"] != ledger["fixture"]["activeActor"] or order["existingRefund"] != existing
                or order.get("preparedStatus") != order["requestedStatus"]
                or order["preparedStatus"] not in ("PAID", "DELIVERED", "RECEIVED")):
            raise FixtureError()
        adapter.preflight(environment)
        _clock_preflight(environment)
        # Preserve a claim even on failure/timeout. A later invocation cannot
        # silently re-run a possibly committed transaction.
        if any(path.parent.glob("probe-*")):
            raise FixtureError()
        with (path.parent / "probe-claim.json").open("x", encoding="utf-8") as stream:
            json.dump({"probe": request["probe"]}, stream)
            stream.flush()
            os.fsync(stream.fileno())
        candidate = path.parent / ("probe-" + request["probe"])
        candidate.mkdir()
        output = candidate
        child = _probe_environment(environment, path, output, request["probe"])
        argv = [_text(environment.get("AFTER_SALES_EVAL_MAVEN_EXE")), "-pl", "mall-server", "-am",
            "-Dtest=AfterSalesDatabaseEvaluationIT#" + PROBE_METHODS[request["probe"]],
            "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
        _atomic_json(output / "launch.json", {"status": "PREPARED", "probe": request["probe"]})
        result = subprocess.run(argv, cwd=str(Path(__file__).resolve().parents[1]),
            shell=False, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300, env=child)
        (output / "maven.stdout").write_text(result.stdout, encoding="utf-8")
        (output / "maven.stderr").write_text(result.stderr, encoding="utf-8")
        if result.returncode != 0:
            raise FixtureError()
        evidence = _read_json(output / "probe-result.json")
        _object(evidence, ("probe", "durationMs", "receiptClass", "assertionsPassed"))
        if (evidence["probe"] != request["probe"] or type(evidence["assertionsPassed"]) is not bool
                or evidence["assertionsPassed"] is not True
                or evidence["receiptClass"] not in ("COMPLETED", "REJECTED", "UNKNOWN", "NOT_APPLICABLE")):
            raise FixtureError()
        _integer(evidence["durationMs"])
        _atomic_json(output / "launch.json", {"status": "COMPLETED", "probe": request["probe"]})
        return _reply("probe", probeEvidence=evidence)
    except Exception as error:
        if output is not None:
            if isinstance(error, subprocess.TimeoutExpired):
                for name, partial in (("maven.stdout", error.stdout), ("maven.stderr", error.stderr)):
                    if partial is not None:
                        (output / name).write_text(partial.decode("utf-8", errors="replace")
                            if isinstance(partial, bytes) else partial, encoding="utf-8")
            _atomic_json(output / "launch.json", {"status": "ERROR", "probe": request["probe"],
                "errorCategory": "FIXTURE_ERROR", "exceptionClass": type(error).__name__})
        return _error("probe")


class Journal:
    def __init__(self, path, ledger, environment):
        self.path, self.ledger, self.environment = path, ledger, environment

    def save(self):
        _atomic_json(self.path, self.ledger)

    def write(self, alias, action, method, path, token, body=None, validate=lambda data: None):
        operation = {"alias": alias, "action": action, "method": method, "path": path,
                     "request": body, "state": "PREPARED"}
        self.ledger["operations"].append(operation)
        self.save()
        try:
            receipt = _http(self.environment, method, path, token, body)
            operation["receipt"] = receipt
            validate(receipt["data"])
        except Exception as error:
            operation["state"] = "UNKNOWN"
            operation["exceptionClass"] = type(error).__name__
            known_rejection = isinstance(error, BusinessError) and error.code not in (-1, 10001)
            self.ledger["status"] = "ERROR" if known_rejection else "UNKNOWN"
            if isinstance(error, BusinessError):
                operation["businessCode"] = error.code
                operation["receipt"] = error.receipt
            self.save()
            if known_rejection:
                raise FixtureError() from None
            raise CreationUnknown() from None
        operation["state"] = "COMPLETED"
        self.save()
        return receipt["data"]


def _product_bindings(data, expected_skus):
    _decimal_id(data["id"])
    if data.get("status") != "ON_SHELF" or not isinstance(data.get("skus"), list) or len(data["skus"]) != len(expected_skus):
        raise FixtureError()
    bindings = {}
    remaining = list(data["skus"])
    for alias, price, specs, marker in expected_skus:
        matches = [sku for sku in remaining if sku.get("specs") == specs
                   and Decimal(str(sku.get("price"))) == price
                   and (marker is None or sku.get("image") == marker)]
        if len(matches) != 1:
            raise FixtureError()
        sku = matches[0]
        remaining.remove(sku)
        bindings[alias] = {"skuId": _decimal_id(sku["id"]), "price": format(price, "f")}
    if len({sku["skuId"] for sku in bindings.values()}) != len(bindings):
        raise FixtureError()
    return {"productId": _decimal_id(data["id"]), "skus": bindings}


def _order_receipt(data, actor, amount, status="PENDING", order_id=None):
    oid = _decimal_id(data["id"])
    _text(data["orderNo"])
    if (_decimal_id(data["userId"]) != actor["userId"]
            or Decimal(str(data["totalAmount"])) != Decimal(amount)
            or data["status"] != status or (order_id is not None and oid != order_id)):
        raise FixtureError()


def _prepare_claimed(request, environment, trial, ledger_path, ledger, prices, amounts, demo):
    fixture = request["fixture"]
    journal = Journal(ledger_path, ledger, environment)
    merchant_token = _merchant(environment)
    for alias in fixture["actors"]:
        nonce = secrets.token_hex(12)
        phone = "1" + str(secrets.randbelow(10**10)).zfill(10)
        payload = {"username": "eval_" + nonce, "password": secrets.token_urlsafe(24), "phone": phone}
        def validate_user(data):
            _decimal_id(data["id"])
            _text(data["accessToken"])
        user = journal.write(alias, "REGISTER", "POST", "/api/auth/register", None, payload, validate_user)
        actor = {"userId": _decimal_id(user["id"]), "userToken": user["accessToken"]}
        ledger["actors"][alias] = actor
        journal.save()
        address = journal.write(alias, "ADDRESS", "POST", "/api/user/address", actor["userToken"],
            {"receiver": "Evaluation User", "phone": phone, "province": "Evaluation Province",
             "city": "Evaluation City", "district": "Evaluation District", "detail": "Fictional fixture address", "isDefault": 1},
            lambda data: _decimal_id(data["id"]))
        actor["addressId"] = _decimal_id(address["id"])
        journal.save()
    active_token = ledger["actors"][fixture["activeActor"]]["userToken"]
    category_id = _category(environment, active_token) if any(p["source"] == "RUN_MUTABLE" for p in fixture["products"].values()) else None
    for alias, product in fixture["products"].items():
        if product["source"] == "RUN_MUTABLE":
            skus = [{"specs": json.dumps(sku["specs"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                     "price": sku["price"], "stock": sku["stock"],
                     "image": f"eval-fixture:{alias}:{sku['skuAlias']}"} for sku in product["skus"]]
            # The optional image field round-trips through the merchant SKU DTO/VO.
            # This internal marker disambiguates identical SKU values without
            # depending on receipt ordering. The helper never fetches it or
            # includes it in Bindings or public NDJSON replies.
            expected = [(sku["skuAlias"], prices[sku["skuAlias"]], wire["specs"], wire["image"])
                        for sku, wire in zip(product["skus"], skus)]
            payload = {"name": product["name"], "description": product["description"],
                       "categoryId": category_id, "status": "ON_SHELF", "skus": skus}
            data = journal.write(alias, "PRODUCT", "POST", "/api/merchant/products", merchant_token, payload,
                                 lambda data: _product_bindings(data, expected))
        else:
            definition = demo[alias]["definition"]
            expected = [(f"{alias}-sku-{index:02d}", prices[f"{alias}-sku-{index:02d}"], sku["specs"], None)
                        for index, sku in enumerate(definition["skus"], 1)]
            data = _http(environment, "GET", "/api/products/" + demo[alias]["productId"], active_token)["data"]
            if (_decimal_id(data["id"]) != demo[alias]["productId"]
                    or data.get("name") != definition["name"] or data.get("description") != definition["description"]):
                raise FixtureError()
        ledger["products"][alias] = _product_bindings(data, expected)
        journal.save()
    sku_bindings = {alias: sku for product in ledger["products"].values() for alias, sku in product["skus"].items()}
    for alias, order in fixture["orders"].items():
        actor = ledger["actors"][order["owner"]]
        token = actor["userToken"]
        data = journal.write(alias, "ORDER", "POST", "/api/orders", token,
            {"addressId": actor["addressId"], "items": [{"skuId": sku_bindings[i["skuAlias"]]["skuId"], "quantity": i["quantity"]} for i in order["items"]]},
            lambda data: _order_receipt(data, actor, amounts[alias]))
        oid, order_no = _decimal_id(data["id"]), data["orderNo"]
        ledger["orders"][alias] = {"orderId": oid, "orderNo": order_no, "owner": order["owner"],
            "expectedPaidAmount": amounts[alias], "requestedAgeSeconds": order["ageSeconds"],
            "requestedStatus": order["status"], "existingRefund": order["existingRefund"]}
        journal.save()
        base = "/api/orders/" + oid
        merchant_base = "/api/merchant/orders/" + quote(order_no, safe="")
        status = order["status"]
        if status == "CANCELLED":
            journal.write(alias, "CANCEL", "PUT", base + "/cancel", token, validate=lambda data: _void(data))
        elif status != "PENDING":
            def validate_payment(data):
                if (_decimal_id(data["orderId"]) != oid or data.get("status") != "SUCCESS"
                        or Decimal(str(data["amount"])) != Decimal(amounts[alias])):
                    raise FixtureError()
            journal.write(alias, "PAY", "POST", base + "/pay", token, validate=validate_payment)
            if status in ("SHIPPED", "DELIVERED", "RECEIVED", "REFUNDED"):
                def logistics(data, expected):
                    if _decimal_id(data["orderId"]) != oid or data.get("status") != expected:
                        raise FixtureError()
                journal.write(alias, "SHIP", "POST", merchant_base + "/ship", merchant_token,
                    {"company": "Evaluation Logistics", "trackingNo": "EVAL" + secrets.token_hex(12)},
                    lambda data: logistics(data, "SHIPPED"))
                if status != "SHIPPED":
                    journal.write(alias, "DELIVER", "POST", merchant_base + "/deliver", merchant_token,
                                  validate=lambda data: logistics(data, "DELIVERED"))
                if status in ("RECEIVED", "REFUNDED"):
                    journal.write(alias, "RECEIVE", "PUT", base + "/receive", token, validate=lambda data: _void(data))
        if order["ageSeconds"]:
            try:
                age_order(str(ledger_path), alias, order["ageSeconds"], environment)
            finally:
                # Keep Journal's object identity and preserve adapter evidence
                # even when a changed age or backend-time check fails.
                refreshed = _read_json(ledger_path)
                if any(refreshed.get(k) != ledger[k] for k in ("runId", "caseId", "trialId", "fixtureHash")):
                    raise FixtureError()
                ledger.clear()
                ledger.update(refreshed)
        if order["existingRefund"] == "PENDING":
            journal.write(alias, "LEGACY_REFUND", "POST", base + "/refund", token,
                          {"reason": "Evaluation legacy pending fixture"}, lambda data: _void(data))
        elif order["existingRefund"] == "REFUNDED":
            def validate_refund(data):
                if (_decimal_id(data["orderId"]) != oid or data.get("eligible") is not True
                        or data.get("refundExists") is not False or data.get("reason") is not None
                        or Decimal(str(data["refundableAmount"])) != Decimal(amounts[alias])):
                    raise FixtureError()
            journal.write(alias, "EXECUTE_REFUND", "POST", base + "/refund/execute", token,
                          {"reason": "Evaluation completed refund fixture"}, validate_refund)
        actual = _http(environment, "GET", base, token)["data"]
        _order_receipt(actual, actor, amounts[alias], status, oid)
        ledger["orders"][alias]["preparedStatus"] = actual["status"]
        journal.save()
    bindings = {"schemaVersion": 1, **{k: request[k] for k in ("runId", "caseId", "trialId")},
        "activeActor": fixture["activeActor"], "actors": {}, "orders": {}, "products": ledger["products"]}
    for alias, actor in ledger["actors"].items():
        bindings["actors"][alias] = {"userId": actor["userId"]}
        if alias == fixture["activeActor"]:
            bindings["actors"][alias]["userToken"] = actor["userToken"]
    for alias, order in ledger["orders"].items():
        bindings["orders"][alias] = {k: order[k] for k in ("orderId", "orderNo")}
    _atomic_json(trial / "bindings.json", bindings)
    ledger["status"] = "COMPLETED"
    journal.save()


def _void(data):
    if data is not None:
        raise FixtureError()


def prepare(request: dict, environment: dict[str, str]) -> dict:
    """Return a closed FixtureReply; private receipts are persisted before reply."""
    ledger_path = None
    ledger = None
    try:
        _validate_request(request, environment)
        if request["op"] != "prepare":
            raise FixtureError()
        prices, amounts, demo = _validate_fixture(request["fixture"], environment)
        root, trial = _scope(request, environment)
        fixture_hash = hashlib.sha256(json.dumps(request["fixture"], sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        ledger_path = trial / "ledger.json"
        relative = ledger_path.relative_to(root).as_posix()
        if trial.exists():
            previous = _read_json(ledger_path)
            if (previous.get("fixtureHash") != fixture_hash
                    or any(previous.get(k) != request[k] for k in ("runId", "caseId", "trialId"))):
                raise FixtureError()
            if previous["status"] == "COMPLETED":
                if not (trial / "bindings.json").is_file():
                    raise FixtureError()
                return _reply("prepare", ledgerPath=relative, bindingFile=(trial / "bindings.json").relative_to(root).as_posix())
            if previous["status"] in ("UNKNOWN", "PREPARED"):
                return _reply("prepare", "UNKNOWN", ledgerPath=relative, errorCategory="FIXTURE_CREATION_UNKNOWN")
            return _error("prepare")
        # An age-dependent fixture needs a configured database and the actual
        # refund uniqueness guard before any business creation is sent.
        if (age_order is _DEFAULT_AGE_ORDER
                and any(o["ageSeconds"] for o in request["fixture"]["orders"].values())):
            adapter = _db_adapter()
            if adapter is None:
                raise FixtureError()
            adapter.preflight(environment)
        if environment.get("AFTER_SALES_EVAL_DB_TEST") == "true":
            _clock_preflight(environment)
        _base_url(environment)
        trial.parent.mkdir(parents=True, exist_ok=True)
        trial.mkdir()  # Exclusive claim: no second caller can recreate this trial.
        ledger = {"schemaVersion": 1, **{k: request[k] for k in ("runId", "caseId", "trialId")},
            "fixtureHash": fixture_hash, "fixture": request["fixture"], "status": "PREPARED",
            "actors": {}, "orders": {}, "products": {}, "operations": []}
        _atomic_json(ledger_path, ledger)
        _prepare_claimed(request, environment, trial, ledger_path, ledger, prices, amounts, demo)
        return _reply("prepare", ledgerPath=relative, bindingFile=(trial / "bindings.json").relative_to(root).as_posix())
    except CreationUnknown:
        return _reply("prepare", "UNKNOWN", ledgerPath=relative, errorCategory="FIXTURE_CREATION_UNKNOWN")
    except Exception as error:
        if ledger is not None:
            ledger["status"] = "ERROR"
            ledger["exceptionClass"] = type(error).__name__
            _atomic_json(ledger_path, ledger)
        return _error("prepare")


def serve(input_stream, output_stream, environment: dict[str, str]) -> None:
    """Consume one closed request per line, flush one safe reply, stop at shutdown."""
    for line in input_stream:
        op = "preflight"
        try:
            request = json.loads(line)
            if isinstance(request, dict) and request.get("op") in OPS:
                op = request["op"]
            _validate_request(request, environment)
            if op == "prepare":
                reply = prepare(request, environment)
            elif op == "preflight":
                preflight(environment)
                reply = _reply(op)
            elif op == "shutdown":
                reply = _reply(op)
            elif op == "oracle":
                projection = oracle(str(_relative_ledger(request, environment)), request["terminalEvidence"], environment)
                reply = _reply(op, oracle=projection)
            elif op == "probe":
                reply = probe(request, environment)
            else:
                reply = _error(op)
        except Exception:
            reply = _error(op)
        output_stream.write(json.dumps(reply, ensure_ascii=False, separators=(",", ":")) + "\n")
        output_stream.flush()
        if op == "shutdown" and reply["status"] == "COMPLETED":
            break


def load_environment(path, inherited=None):
    """Load allowlisted local configuration only; no shell expansion or logging."""
    environment = {k: v for k, v in (inherited if inherited is not None else os.environ).items() if k in ENV_KEYS}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key, value = key.strip(), value.strip()
        if key not in ENV_KEYS:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        environment.setdefault(key, value)
    return environment


def main(argv=None):
    parser = argparse.ArgumentParser(description="Private supermall fixture NDJSON helper")
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--demo-catalog", help="Committed logical demo catalog path")
    parser.add_argument("--demo-manifest", help="Ignored local logical-key to product-ID allowlist path")
    parser.add_argument("--mysql-exe", help="Native mysql executable when absent from PATH")
    parser.add_argument("--java-home", help="Probe JDK directory; JDK 22 is the validated runtime")
    parser.add_argument("--maven-exe", help="Native Maven launcher path, including paths with spaces")
    args = parser.parse_args(argv)
    try:
        environment = load_environment(args.env_file)
        environment["AFTER_SALES_EVAL_OUTPUT_ROOT"] = args.output_root
        if args.demo_catalog:
            environment["AFTER_SALES_EVAL_DEMO_CATALOG"] = args.demo_catalog
        if args.demo_manifest:
            environment["AFTER_SALES_EVAL_DEMO_MANIFEST"] = args.demo_manifest
        for value, key in ((args.mysql_exe, "MYSQL_EXE"), (args.java_home, "JAVA_HOME"),
                           (args.maven_exe, "AFTER_SALES_EVAL_MAVEN_EXE")):
            if value:
                environment[key] = value
        serve(sys.stdin, sys.stdout, environment)
        return 0
    except Exception:
        # Startup failures also have no raw filesystem/credential exception text.
        sys.stdout.write(json.dumps(_error("preflight"), separators=(",", ":")) + "\n")
        sys.stdout.flush()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
