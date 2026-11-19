load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")
load("//tools/bzl:junit.bzl", "junit_tests")

config_setting(
    name = "larger_heap",
    define_values = {"acceptance_heap": "large"},
    visibility = ["//visibility:public"],
)

junit_tests(
    name = "acceptance_framework_tests",
    srcs = glob(["**/*.java"], exclude = ["WalGerritChangeKeyRaceIT.java"]),
    deps = [
        "//java/com/google/gerrit/acceptance:lib",
        "//java/com/google/gerrit/acceptance/testsuite/group",
        "//java/com/google/gerrit/server/util/time",
        "//java/com/google/gerrit/testing:gerrit-test-util",
        "//java/com/google/gerrit/truth",
        "//lib:guava",
        "//lib:jgit",
        "//lib/truth",
    ],
)

# The local-backend acceptance runner supplies the Maven-built storage library.
acceptance_tests(
    srcs = ["WalGerritChangeKeyRaceIT.java"],
    group = "WalGerritChangeKeyRaceIT",
    labels = ["manual"],
)
