load("@rules_java//java:defs.bzl", "java_library")
load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")

# The WalGerrit library's own source, not a copy of it.
java_library(
    name = "change_merged_reconstruction",
    testonly = True,
    srcs = [
        "//plugins/walgerrit:src/main/java/dev/walgerrit/ChangeMergedReconstruction.java",
        "//plugins/walgerrit:src/main/java/dev/walgerrit/ChangeRefIds.java",
    ],
    deps = [
        "//java/com/google/gerrit/common:annotations",
        "//java/com/google/gerrit/entities",
        "//java/com/google/gerrit/server",
        "//lib:jgit",
        "//lib/guice",
    ],
)

acceptance_tests(
    srcs = glob(["*IT.java"]),
    group = "walgerrit_events",
    labels = ["server"],
    deps = [":change_merged_reconstruction"],
)
