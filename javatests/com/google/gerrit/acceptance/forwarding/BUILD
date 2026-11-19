load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")

acceptance_tests(
    srcs = glob(["*IT.java"]),
    group = "write_forwarding",
    labels = ["ssh"],
    deps = [
        "//java/com/google/gerrit/sshd",
        "//javatests/com/google/gerrit/util/http/testutil",
        "//lib/mina:sshd",
    ],
)
