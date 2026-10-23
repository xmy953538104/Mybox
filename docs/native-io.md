# Native file paths

Mybox passes the same per-guest Java I/O rules to the native layer before guest
application code starts. `PathRules` publishes immutable snapshots; reads require
no JNI, filesystem calls, or per-call string allocation. Directory matches use
component boundaries and the longest matching prefix. Already mapped destination
paths and explicit Pictures whitelist paths pass through unchanged. Rules own
their strings; JNI releases its temporary strings after registration.

`LibcHook` installs source-built Dobby hooks in the guest process for exported
libc file APIs: open/openat and their large-file/fortify variants, stdio opens,
directory opens, stat/lstat/fstatat/statfs, access, creation/removal, rename,
link/symlink, readlink, ownership/permissions, timestamps, truncation, chdir, and
inotify watches. Shared-address aliases are installed once. An outer call maps
into stack buffers; a thread-local guard prevents nested libc calls from mapping
again. The real function receives its original flags, modes, file descriptors,
and remaining arguments. Return values and errno come from that real operation;
a mapped path that exceeds PATH_MAX fails with ENAMETOOLONG.

Relative `*at` paths remain relative to the supplied real directory descriptor.
Absolute paths use the existing container directory. No Binder identity, system
package, Root access, or Xposed mechanism is changed. Failure to install a required
hook stops guest initialization instead of reporting a working container.

The pure C++ regression test can run on a host:

```sh
c++ -std=c++17 -pthread Bcore/src/test/cpp/PathRulesTest.cpp -o /tmp/path-rules-test
/tmp/path-rules-test
```

The CMake target `path-rules-test` builds the same test for Android, outside the
APK. It checks boundaries, longest-prefix selection, destination idempotence,
whitelist handling, null/relative inputs, buffer limits, and eight readers during
rule updates. The guest probe separately exercises real openat/fsync/stat/rename,
relative directory-descriptor reads, unlink and ENOENT. A library build alone is
not evidence that those operations pass on a device.

`shortcut:` This is application compatibility, not a security sandbox. Raw
syscalls, private libc syscall stubs, directly executed subprocesses, and linker
namespace virtualization are not intercepted; add a proven failing application
case before expanding the interception surface. Symlink and `..` traversal retain
normal kernel semantics, and returned physical paths are not disguised. This
does not isolate mutually hostile native code sharing the host app UID.

`shortcut:` Hooks install once before guest code starts, with an original-call
pointer published before each patch. General stop-the-world patching of arbitrary
running native code is not implemented. Test each Android build and CPU before
claiming support, particularly 16 KiB-page or new instruction-security devices.

Source identity, local changes, and third-party licenses are recorded in
[`MYBOX-NOTES.md`](../Bcore/src/main/cpp/third_party/dobby/MYBOX-NOTES.md).
