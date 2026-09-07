import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("build_version", Path(__file__).with_name("build-version.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseVersionTest(unittest.TestCase):
    def test_numeric_tags(self):
        for version in ("1.0.0", "12.34.56", "0.0.0"):
            module.validate_tag(f"v{version}", version)

    def test_invalid_tags(self):
        for tag in ("1.0.0", "v1.0", "v1.0.0-rc1", "v1.0.0foo", "v1.0.0\n", "master", "v١.0.0"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                module.validate_tag(tag, "1.0.0")

    def test_tag_must_match_project_version(self):
        with self.assertRaises(ValueError):
            module.validate_tag("v1.0.1", "1.0.0")


if __name__ == "__main__":
    unittest.main()
