"""Dependency-free checks for local tool discovery; no browser or Java process is started."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("demo_launcher", Path(__file__).with_name("demo.py"))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class ToolDiscoveryTest(unittest.TestCase):
    def test_maven_on_path(self):
        with patch.object(demo.shutil, "which", return_value="/tools/mvn"):
            self.assertEqual(demo.find_maven(None, {}), "/tools/mvn")

    def test_explicit_maven_does_not_silently_fallback(self):
        with patch.object(demo.shutil, "which", return_value=None):
            self.assertIsNone(demo.find_maven("/missing/mvn", {}))

    def test_intellij_maven_without_path(self):
        bundled = Path("/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn")
        with patch.object(demo.shutil, "which", return_value=None), \
                patch.object(demo.sys, "platform", "darwin"), \
                patch.object(Path, "glob", return_value=iter([bundled])), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(demo.os, "access", return_value=True):
            self.assertEqual(demo.find_maven(None, {}), str(bundled))

    def test_maven_home(self):
        with patch.object(demo.shutil, "which", return_value=None), \
                patch.object(demo.sys, "platform", "linux"), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(demo.os, "access", return_value=True):
            self.assertEqual(demo.find_maven(None, {"MAVEN_HOME": "/tools/maven"}),
                             "/tools/maven/bin/mvn")

    def test_explicit_java_home(self):
        with patch.object(demo, "java_major", return_value=25):
            self.assertEqual(demo.find_java_home("/tools/jdk", {}), Path("/tools/jdk"))

    def test_wrong_java_home_is_rejected(self):
        with patch.object(demo, "java_major", return_value=21):
            self.assertIsNone(demo.find_java_home(None, {"JAVA_HOME": "/tools/jdk21"}))

    def test_java_discovered_from_ide_download_directory(self):
        home = Path("/synthetic/jdk-25/Contents/Home")
        with patch.object(Path, "glob", side_effect=lambda _: iter([home])), \
                patch.object(demo.shutil, "which", return_value="/usr/bin/java"), \
                patch.object(demo, "java_major", return_value=25):
            self.assertEqual(demo.find_java_home(None, {}), home)

    def test_java_release_metadata(self):
        with patch.object(Path, "is_file", return_value=True), \
                patch.object(Path, "read_text", return_value='JAVA_VERSION="25.0.2"\n'):
            self.assertEqual(demo.java_major(Path("/tools/jdk")), 25)


if __name__ == "__main__":
    unittest.main()
