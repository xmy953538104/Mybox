
#include "dobby/dobby_internal.h"
#include "PlatformUnifiedInterface/ExecMemory/ClearCacheTool.h" // Mybox: current upstream header.

#include <unistd.h>
#include <sys/mman.h>
#include <string.h>

#if !defined(__APPLE__)
PUBLIC int DobbyCodePatch(void *address, uint8_t *buffer, uint32_t buffer_size) {
#if defined(__ANDROID__) || defined(__linux__)
  int page_size = (int)sysconf(_SC_PAGESIZE);
  uintptr_t patch_page = ALIGN_FLOOR(address, page_size);
  if (!address || !buffer || !buffer_size) return -1;
  uintptr_t patch_end_page = ALIGN_FLOOR((uintptr_t)address + buffer_size - 1, page_size);

  // Mybox: do not write read-only code or claim success if mprotect is denied.
  if (mprotect((void *)patch_page, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0)
    return -1;
  if (patch_page != patch_end_page) {
    if (mprotect((void *)patch_end_page, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
      mprotect((void *)patch_page, page_size, PROT_READ | PROT_EXEC);
      return -1;
    }
  }

  // patch buffer
  memcpy(address, buffer, buffer_size);

  // restore page permission
  int result = mprotect((void *)patch_page, page_size, PROT_READ | PROT_EXEC);
  if (patch_page != patch_end_page) {
    result |= mprotect((void *)patch_end_page, page_size, PROT_READ | PROT_EXEC);
  }

  addr_t clear_start_ = (addr_t)address;
  ClearCache((void *)clear_start_, (void *)(clear_start_ + buffer_size));
  return result;
#endif
  return 0;
}

#endif