# Dobby source used by Mybox

Upstream: <https://github.com/jmpews/Dobby>

Commit: `5dfc8546954ce3b3198132ab13fddb89ee92cdd7`

Source archive: <https://codeload.github.com/jmpews/Dobby/zip/5dfc8546954ce3b3198132ab13fddb89ee92cdd7>

Archive SHA-256: `15b4c5cbe043f132d21f367ad0ddb526a41a657f2869f37c09d9302996dfc0e3`

The `source`, `include`, `common`, `external`, and `cmake` directories and root
build/license files come from this archive. Mybox builds `dobby_static` from
source for Android arm64-v8a and x86_64. No prebuilt libraries, plugins, examples,
tests, private kernel mode, or symbol resolver are linked. Some unused upstream
platform sources remain in this source snapshot for straightforward comparison.

The following local corrections are required by the pinned source and NDK 27:

- `CMakeLists.txt`: honor explicit disabled logging, use a fixed source revision
  as the version, pass compiler definitions as separate arguments, and declare
  only the static target so AGP does not also package an unused `libdobby.so`.
- `cmake/compiler_and_linker.cmake`: preprocess `.asm` architecture guards for
  x86_64 as well as ARM64.
- `closure_bridge_arm64.asm`: use ELF GOT relocations on Android, retaining
  upstream Mach-O syntax for Apple builds.
- `common/os_arch_features.h`: remove the circular platform include; use the
  Android page permission API directly and return failure when it is denied.
- `PlatformUtil/Linux/ProcessRuntime.cc`: align member names with this revision's
  headers, check `fgets` and long lines, preserve permission bits, clear the module
  snapshot before reuse, and bound module path copies.
- `InlineHookRouting.h`: publish the original trampoline before activating the
  replacement, and propagate permission/patch failures.
- `code-patch-tool-posix.cc`: include the current cache header, bound the patch
  pages correctly, and check writable and executable permission changes.

The library's Apache-2.0 license is in `LICENSE`. Embedded TinySTL notices are
retained in its headers and `TINYSTL-LICENSE.txt` (BSD-2-Clause). The cache flush
implementation carries Apache-2.0 with LLVM exceptions; its complete license is
in `LLVM-LICENSE.txt`. Unused V8 semaphore sources and Apple logging headers retain
their original notices, with `V8-LICENSE.txt` and `APPLE-LICENSE.txt` supplied.
