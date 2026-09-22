load(
    "//tools/bzl:plugin.bzl",
    "gerrit_plugin",
)

gerrit_plugin(
    name = "rename-project",
    srcs = glob(["src/main/java/**/*.java"]),
    manifest_entries = [
        "Gerrit-PluginName: rename-project",
        "Gerrit-Module: com.criteo.gerrit.renameproject.Module",
        "Gerrit-SshModule: com.criteo.gerrit.renameproject.SshModule",
    ],
    resources = glob(["src/main/resources/**/*"]),
)
