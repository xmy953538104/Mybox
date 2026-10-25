"""File boundaries for optional local Play assets; never a Google signature trust source."""
import re

MAX_FILES = 256
MAX_BYTES = 512 * 1024 * 1024
DIRECTORY = "assets/mybox/google-play/"
SINGLE = "assets/mybox/google-play.apk"


def members(names):
    names = list(names)
    if not 1 <= len(names) <= MAX_FILES:
        raise ValueError("Store set requires 1 to 256 APKs")
    seen = set()
    for name in names:
        if (not isinstance(name, str) or len(name) > 160
                or not (name == "base.apk" or re.fullmatch(r"split_[A-Za-z0-9_][A-Za-z0-9_.-]*\.apk", name))
                or name.casefold() in seen):
            raise ValueError("Invalid or duplicate Store APK filename")
        seen.add(name.casefold())
    if "base.apk" not in names:
        raise ValueError("Store APK set is missing base.apk")
    return sorted(names)
