#!/usr/bin/env python3
"""Regression guard for body/credential logging on the built service paths."""

import argparse
import re
import unittest
from pathlib import Path


ROOTS = (
    "play-services-base/core/src/main/java/org/microg/gms/common/HttpFormClient.java",
    "play-services-core/src/main/java/org/microg/gms/auth",
    "play-services-core/src/main/java/org/microg/gms/checkin",
    "play-services-core/src/main/java/org/microg/gms/gcm",
    "play-services-core/src/main/kotlin/org/microg/gms/auth",
    "play-services-core/src/main/kotlin/org/microg/gms/gcm",
)
RAW_NAMES = (
    "content|result|request|response|extras|bundle|responseBundle|requestOptions|"
    "credentialRequest|credData|dataBase64|consentResponse|identifierResponse|"
    "notificationData|responseJson|googleSignInAccount|deviceKey|proto|regId|"
    "accountName|accountList|authStatusList|url|token|authToken|cookie|password"
)
RAW_VALUE = re.compile(r"(?:\+\s*|\$\{?)(?:" + RAW_NAMES + r")\b")
SAFE_VALUE = re.compile(
    r"^\$\{?\w+(?:\?\.\w+)*\s*(?:!=|==)\s*null\}|"
    r"^\$\{\w+(?:\??\.\w+)*\.(?:size|statusCode|code|type)\b|"
    r"^\$\{\w+\?\.members\?\.size\b|"
    r"^\$\{\w+\.javaClass\.simpleName\}|"
    r"^\+\s*\w+\.getClass\(\)\.getSimpleName\(\)"
)


def sensitive_log_lines(source):
    for number, line in enumerate(source.splitlines(), 1):
        if not re.search(r"\bLog\.[vdiew]\(", line):
            continue
        if '"hasCapabilities(" + Arrays.toString(request.capabilities) + ") = " + result' in line:
            continue  # result is the capability bit mask, not an auth response.
        # These are only static sinks; this does not certify arbitrary runtime exceptions.
        raw = any(not SAFE_VALUE.match(line[m.start():]) for m in RAW_VALUE.finditer(line))
        if (raw or "buildTokenKey(" in line
                or re.search(r"\$\{[^}]*\b(?:googleIdToken|requestJson)\b", line)
                or re.search(r"(?:\+\s*|\$\{?)(?:intent|outIntent|data)\??\.extras\b(?!\?\.keySet\(\))", line)
                or re.search(r"\+\s*(?:message|msg\.obj)\s*[,);]", line)
                or re.search(r"\$\{(?:error\.networkResponse\.data|e\.message)\}", line)):
            yield number


def audit(root):
    count = 0
    failures = []
    for relative in ROOTS:
        path = root / relative
        if not path.exists():
            raise ValueError(f"Missing source path: {relative}")
        files = [path] if path.is_file() else sorted(path.rglob("*"))
        for source in files:
            if source.suffix not in (".java", ".kt"):
                continue
            count += 1
            failures.extend(f"{source.relative_to(root).as_posix()}:{line}"
                            for line in sensitive_log_lines(source.read_text(encoding="utf-8")))
    if failures:
        raise ValueError("Potential sensitive log sink(s):\n" + "\n".join(failures))
    print(f"Service logging regression audit passed: {count} source files")


class AuditTests(unittest.TestCase):
    def test_raw_bodies_and_tokens_rejected(self):
        for body in ('Log.d(TAG, "Request" + request);', 'Log.d(TAG, "Result: $response")',
                     'Log.d(TAG, "data: ${intent.extras}")',
                     'Log.d(TAG, "peekAuthToken: " + buildTokenKey());',
                     'Log.d(TAG, "token=${signInCredential.googleIdToken?.take(50)}")',
                     'Log.d(TAG, "Outgoing" + msg.obj);'):
            self.assertEqual(list(sensitive_log_lines(body)), [1])

    def test_status_and_types_stay_visible(self):
        for body in ('Log.d(TAG, "HTTP " + status);',
                     'Log.w(TAG, "Failed", e);',
                     'Log.d(TAG, "Received: ${result != null}")',
                     'Log.d(TAG, "Kind: " + request.getClass().getSimpleName());',
                     'Log.d(TAG, "Count: ${response?.members?.size ?: 0}")'):
            self.assertEqual(list(sensitive_log_lines(body)), [])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=[__file__])
    elif args.source:
        audit(args.source)
    else:
        parser.error("--source or --self-test is required")
