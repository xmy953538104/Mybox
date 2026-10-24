"""Accept only the pinned pristine tree or the exact complete microG patch series."""
import argparse
import os
import pathlib
import re
import subprocess
import tempfile


class SourceStateError(RuntimeError):
    pass


def git(source, *args, index=None, data=None, check=True):
    env = os.environ.copy()
    for name in ("GIT_DIR", "GIT_COMMON_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE",
                 "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES"):
        env.pop(name, None)
    env["GIT_OPTIONAL_LOCKS"] = "0"
    if index is not None:
        env["GIT_INDEX_FILE"] = str(index)
    result = subprocess.run(["git", "-C", str(source), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if check and result.returncode:
        raise SourceStateError("Git source validation/application failed at " + args[0]
                               + "; no reset was performed: "
                               + result.stderr.decode("utf-8", errors="replace").strip())
    return result


def prepare(source, commit, patches, diff_output):
    source = pathlib.Path(source).resolve()
    diff_output = pathlib.Path(diff_output).resolve()
    patches = [pathlib.Path(patch).resolve() for patch in patches]
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not patches or not all(p.is_file() for p in patches):
        raise SourceStateError("A full pinned commit and nonempty patch series are required")
    if source == diff_output or source in diff_output.parents:
        raise SourceStateError("The recorded diff must be outside the source checkout")
    top = pathlib.Path(git(source, "rev-parse", "--show-toplevel").stdout.decode().strip()).resolve()
    if top != source or git(source, "rev-parse", "HEAD").stdout.decode().strip() != commit:
        raise SourceStateError("The source checkout must be at the exact pinned upstream commit")
    # Preserve deliberate staged work. Old --intent-to-add entries are safe and stay untouched.
    if git(source, "diff", "--cached", "--quiet", "--ita-invisible-in-index", commit,
           "--", check=False).returncode:
        raise SourceStateError("Staged source changes are present; leave them intact and use a fresh checkout")

    base_tree = git(source, "rev-parse", commit + "^{tree}").stdout.strip()
    with tempfile.TemporaryDirectory(prefix="mybox-microg-index-") as directory:
        index = pathlib.Path(directory) / "index"
        git(source, "read-tree", commit, index=index)
        # Real sequential cached application handles later patches editing earlier new files.
        # --check on a list of patches does not simulate those intermediate states.
        for patch in patches:
            git(source, "apply", "--cached", str(patch), index=index)
        expected_tree = git(source, "write-tree", index=index).stdout.strip()
        changes = git(source, "diff", "--cached", "--binary", commit, "--", index=index).stdout

        def working_tree():
            git(source, "read-tree", commit, index=index)
            git(source, "add", "--all", "--", ".", index=index)
            return git(source, "write-tree", index=index).stdout.strip()

        actual_tree = working_tree()
        if actual_tree == expected_tree:
            state = "already applied"
        elif actual_tree == base_tree:
            # Apply one prevalidated final diff without staging or modifying the main index.
            git(source, "apply", "--binary", "-", data=changes)
            if working_tree() != expected_tree:
                raise SourceStateError("Source changed during application; no reset was performed")
            state = "applied"
        else:
            raise SourceStateError("Source is neither pristine nor the exact complete patch series; "
                                   "mixed, older or additional source changes were left intact")
        diff_output.write_bytes(changes)
        return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("commit")
    parser.add_argument("diff_output")
    parser.add_argument("patches", nargs="+")
    args = parser.parse_args()
    try:
        state = prepare(args.source, args.commit, args.patches, args.diff_output)
    except (SourceStateError, OSError) as failure:
        parser.exit(1, "microG source rejected: " + str(failure) + "\n")
    print("microG exact patch series: " + state)


if __name__ == "__main__":
    main()
