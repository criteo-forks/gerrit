load("@rules_java//java:defs.bzl", "java_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "PLUGIN_DEPS",
    "PLUGIN_TEST_DEPS",
    "gerrit_plugin",
)

# The LDAP password check lives in the server, outside the plugin API.
java_library(
    name = "auth-neverlink",
    neverlink = 1,
    exports = ["//java/com/google/gerrit/auth"],
)

gerrit_plugin(
    name = "oidc",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: oidc",
        "Gerrit-Module: com.googlesource.gerrit.plugins.oidc.Module",
        "Gerrit-HttpModule: com.googlesource.gerrit.plugins.oidc.HttpModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [":auth-neverlink"],
)

java_library(
    name = "oidc_test_util",
    testonly = 1,
    srcs = glob(["src/test/java/**/Fake*.java"]),
    deps = [":oidc__plugin_test_deps"],
)

junit_tests(
    name = "oidc_tests",
    srcs = glob(["src/test/java/**/*Test.java"]),
    deps = [
        ":oidc__plugin_test_deps",
        ":oidc_test_util",
    ],
)

java_library(
    name = "oidc__plugin_test_deps",
    testonly = 1,
    visibility = ["//visibility:public"],
    exports = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        ":oidc__plugin",
        "//java/com/google/gerrit/auth",
        "//java/com/google/gerrit/server/securestore/testing",
        "//lib/mockito",
    ],
)
