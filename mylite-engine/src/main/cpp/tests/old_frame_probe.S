/* SPDX-License-Identifier: Apache-2.0 */
/* Independent diagnostic assembly, not linked into the Mylite engine. */
#include <asm/unistd.h>
.text
#if defined(__aarch64__)
.macro FUNCTION name
    .p2align 4
    .global \name
    .hidden \name
    .type \name,%function
\name:
.endm
.macro END name
    .size \name,.-\name
.endm
.macro LABEL name
    .global \name
    .hidden \name
\name:
.endm
FUNCTION old_probe_raw
    mov x8,x0
    mov x0,x1
    mov x1,x2
    mov x2,x3
    mov x3,x4
    mov x4,x5
    mov x5,x6
    svc #0
    ret
END old_probe_raw

FUNCTION old_probe_getpid
    mov x8,#__NR_getpid
    svc #0
LABEL old_probe_getpid_pc
    ret
END old_probe_getpid

/* No call/prologue/stack change: the kernel consumes the genuine frame at SP. */
    nop
FUNCTION old_probe_restorer
    mov x8,#__NR_rt_sigreturn
LABEL old_probe_restorer_syscall
    svc #0
LABEL old_probe_restorer_pc
    brk #0
END old_probe_restorer

/* Adjacent, deliberately non-exempt return site. */
FUNCTION old_probe_neighbor_restorer
    mov x8,#__NR_rt_sigreturn
    svc #0
LABEL old_probe_neighbor_pc
    brk #0
END old_probe_neighbor_restorer

FUNCTION old_probe_handoff
    mov x8,#__NR_rt_sigreturn
    svc #0
LABEL old_probe_handoff_pc
    brk #0
END old_probe_handoff

FUNCTION old_probe_wrong_nr
    mov x8,#__NR_getpid
    b old_probe_restorer_syscall
END old_probe_wrong_nr

FUNCTION old_probe_bad_restorer
    mov x9,#0x1000
    mov sp,x9
    mov x8,#__NR_rt_sigreturn
    svc #0
    brk #0
END old_probe_bad_restorer

FUNCTION old_probe_overflow_restorer
    mov x9,#-16
    mov sp,x9
    mov x8,#__NR_rt_sigreturn
    svc #0
    brk #0
END old_probe_overflow_restorer

/* Protect the old mask only after the ordinary C handler has returned. */
FUNCTION old_probe_readonly_restorer
    add x0,sp,#168
    adrp x9,old_probe_page_mask
    ldr x9,[x9,:lo12:old_probe_page_mask]
    and x0,x0,x9
    adrp x9,old_probe_page_size
    ldr x1,[x9,:lo12:old_probe_page_size]
    mov x2,#1
    mov x8,#__NR_mprotect
    svc #0
    cbnz x0,1f
    mov x8,#__NR_rt_sigreturn
    svc #0
    brk #0
1:  mov x0,#94
    mov x8,#__NR_exit_group
    svc #0
    brk #0
END old_probe_readonly_restorer

/* pid, tid, signal, &expected_sp. Preserve/check integer and full 128-bit SIMD. */
FUNCTION old_probe_invoke
    stp x19,x20,[sp,#-32]!
    str q15,[sp,#16]
    mov x9,sp
    str x9,[x3]
    mov x20,x0
    mov x19,#0x1234
    mov x9,#0x5678
    dup v15.2d,x9
    mov x8,#__NR_tgkill
    svc #0
    cmp x0,#0
    cset x10,ne
    /* x10 itself is not a sentinel: this syscall is after the signal return. */
    mov x8,#__NR_getpid
    svc #0
LABEL old_probe_invoke_getpid_pc
    cmp x0,x20
    cset x9,ne
    orr x10,x10,x9,lsl #1
    mov x9,#0x1234
    cmp x19,x9
    cset x9,ne
    orr x10,x10,x9,lsl #2
    fmov x9,d15
    mov x11,#0x5678
    cmp x9,x11
    cset x9,ne
    orr x10,x10,x9,lsl #3
    mov x9,v15.d[1]
    cmp x9,x11
    cset x9,ne
    orr x0,x10,x9,lsl #4
    ldr q15,[sp,#16]
    ldp x19,x20,[sp],#32
    ret
END old_probe_invoke
#elif defined(__x86_64__)
.macro FUNCTION name
    .p2align 4
    .global \name
    .hidden \name
    .type \name,@function
\name:
.endm
.macro END name
    .size \name,.-\name
.endm
.macro LABEL name
    .global \name
    .hidden \name
\name:
.endm
FUNCTION old_probe_raw
    mov %rdi,%rax
    mov %rsi,%rdi
    mov %rdx,%rsi
    mov %rcx,%rdx
    mov %r8,%r10
    mov %r9,%r8
    mov 8(%rsp),%r9
    syscall
    ret
END old_probe_raw

FUNCTION old_probe_getpid
    mov $__NR_getpid,%eax
    syscall
LABEL old_probe_getpid_pc
    ret
END old_probe_getpid

    nop
FUNCTION old_probe_restorer
    mov $__NR_rt_sigreturn,%eax
LABEL old_probe_restorer_syscall
    syscall
LABEL old_probe_restorer_pc
    ud2
END old_probe_restorer
FUNCTION old_probe_neighbor_restorer
    mov $__NR_rt_sigreturn,%eax
    syscall
LABEL old_probe_neighbor_pc
    ud2
END old_probe_neighbor_restorer
FUNCTION old_probe_handoff
    mov $__NR_rt_sigreturn,%eax
    syscall
LABEL old_probe_handoff_pc
    ud2
END old_probe_handoff
FUNCTION old_probe_wrong_nr
    mov $__NR_getpid,%eax
    jmp old_probe_restorer_syscall
END old_probe_wrong_nr
FUNCTION old_probe_bad_restorer
    mov $0x1000,%esp
    mov $__NR_rt_sigreturn,%eax
    syscall
    ud2
END old_probe_bad_restorer
FUNCTION old_probe_overflow_restorer
    mov $-8,%rsp
    mov $__NR_rt_sigreturn,%eax
    syscall
    ud2
END old_probe_overflow_restorer
FUNCTION old_probe_readonly_restorer
    lea 296(%rsp),%rdi
    and old_probe_page_mask(%rip),%rdi
    mov old_probe_page_size(%rip),%rsi
    mov $1,%edx
    mov $__NR_mprotect,%eax
    syscall
    test %rax,%rax
    jnz 1f
    mov $__NR_rt_sigreturn,%eax
    syscall
    ud2
1:  mov $94,%edi
    mov $__NR_exit_group,%eax
    syscall
    ud2
END old_probe_readonly_restorer
FUNCTION old_probe_invoke
    push %rbx
    push %r12
    sub $16,%rsp
    movdqu %xmm15,(%rsp)
    mov %rsp,(%rcx)
    mov %rdi,%r12
    mov $0x1234,%ebx
    mov $0x5678,%eax
    movq %rax,%xmm15
    punpcklqdq %xmm15,%xmm15
    mov $__NR_tgkill,%eax
    syscall
    test %rax,%rax
    setne %r8b
    movzbl %r8b,%r8d
    mov $__NR_getpid,%eax
    syscall
LABEL old_probe_invoke_getpid_pc
    cmp %r12,%rax
    setne %r9b
    movzbl %r9b,%r9d
    shl $1,%r9d
    or %r9d,%r8d
    cmp $0x1234,%rbx
    setne %r9b
    movzbl %r9b,%r9d
    shl $2,%r9d
    or %r9d,%r8d
    movq %xmm15,%rax
    cmp $0x5678,%rax
    setne %r9b
    movzbl %r9b,%r9d
    shl $3,%r9d
    or %r9d,%r8d
    movdqa %xmm15,%xmm14
    psrldq $8,%xmm14
    movq %xmm14,%rax
    cmp $0x5678,%rax
    setne %r9b
    movzbl %r9b,%r9d
    shl $4,%r9d
    or %r9d,%r8d
    mov %r8d,%eax
    movdqu (%rsp),%xmm15
    add $16,%rsp
    pop %r12
    pop %rbx
    ret
END old_probe_invoke
#else
#error The independent probe supports native LP64 ARM64 and x86_64 only
#endif
.section .note.GNU-stack,"",%progbits
