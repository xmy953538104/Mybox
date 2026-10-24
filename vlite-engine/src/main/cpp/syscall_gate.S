/* SPDX-License-Identifier: Apache-2.0 */
#if defined(__aarch64__)
.text
.p2align 4
.global mybox_raw_syscall
.hidden mybox_raw_syscall
.type mybox_raw_syscall,%function
mybox_raw_syscall:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
    ret
.global mybox_raw_syscall_end
.hidden mybox_raw_syscall_end
mybox_raw_syscall_end:
.size mybox_raw_syscall, .-mybox_raw_syscall

.global mybox_dispatch_on_stack
.hidden mybox_dispatch_on_stack
.type mybox_dispatch_on_stack,%function
mybox_dispatch_on_stack:
    stp x19, x30, [sp, #-16]!
    mov x19, sp
    mov sp, x1
    bl mybox_dispatch
    mov sp, x19
    ldp x19, x30, [sp], #16
    ret
.size mybox_dispatch_on_stack, .-mybox_dispatch_on_stack
#elif defined(__x86_64__)
.text
.p2align 4
.global mybox_raw_syscall
.hidden mybox_raw_syscall
.type mybox_raw_syscall,@function
mybox_raw_syscall:
    mov %rdi, %rax
    mov %rsi, %rdi
    mov %rdx, %rsi
    mov %rcx, %rdx
    mov %r8, %r10
    mov %r9, %r8
    mov 8(%rsp), %r9
    syscall
    ret
.global mybox_raw_syscall_end
.hidden mybox_raw_syscall_end
mybox_raw_syscall_end:
.size mybox_raw_syscall, .-mybox_raw_syscall

.global mybox_dispatch_on_stack
.hidden mybox_dispatch_on_stack
.type mybox_dispatch_on_stack,@function
mybox_dispatch_on_stack:
    push %r12
    mov %rsp, %r12
    mov %rsi, %rsp
    and $-16, %rsp
    call mybox_dispatch
    mov %r12, %rsp
    pop %r12
    ret
.size mybox_dispatch_on_stack, .-mybox_dispatch_on_stack
#else
#error Unsupported native I/O ABI
#endif
.section .note.GNU-stack,"",%progbits
