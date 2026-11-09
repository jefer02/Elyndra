import unittest
from datetime import datetime, timezone

from portscat.trust import RepoStats, compute

NOW = datetime(2026, 11, 8, tzinfo=timezone.utc)
OLD = "2024-03-01T00:00:00Z"
NEW = "2026-06-08T00:00:00Z"


class Trust(unittest.TestCase):
    def test_age_stars_and_contributors_together_verify(self):
        self.assertTrue(compute(RepoStats("a/b", OLD, 100, 3), None, set(), NOW).verified)

    def test_each_shortfall_is_named(self):
        t = compute(RepoStats("a/b", NEW, 99, 2), None, set(), NOW)
        self.assertFalse(t.verified)
        self.assertEqual("repo < 1 year, < 100 stars, < 3 contributors", t.reason)
        # Old with lots of stars but one person: still unverified (zelda3-android's case).
        self.assertEqual("< 3 contributors", compute(RepoStats("w/zelda3-android", OLD, 208, 1), None, set(), NOW).reason)

    def test_a_github_fork_of_a_project_that_meets_them_is_verified(self):
        parent = RepoStats("HarbourMasters/SpaghettiKart", OLD, 900, 40)
        t = compute(RepoStats("izzy2lost/SpaghettiKart", NEW, 20, 1), parent, set(), NOW)
        self.assertTrue(t.verified)
        self.assertIn("HarbourMasters/SpaghettiKart", t.reason)

    def test_a_fork_of_a_small_project_is_not(self):
        parent = RepoStats("x/small", OLD, 10, 1)
        self.assertFalse(compute(RepoStats("y/fork", NEW, 5, 1), parent, set(), NOW).verified)

    def test_without_a_github_fork_relationship_nothing_is_inherited(self):
        # A README may claim an upstream: that doesn't count, only `parent` (GitHub's fork link) does.
        self.assertFalse(compute(RepoStats("linkzenic/Shipwright-Ports", NEW, 101, 4), None, set(), NOW).verified)

    def test_trusted_yaml_forces_verified(self):
        t = compute(RepoStats("Some/Repo", NEW, 1, 1), None, {"some/repo"}, NOW)
        self.assertTrue(t.verified)
        self.assertEqual("listed in trusted.yaml", t.reason)

    def test_exactly_one_year_counts(self):
        self.assertTrue(compute(RepoStats("a/b", "2025-11-08T00:00:00Z", 100, 3), None, set(), NOW).verified)


if __name__ == "__main__":
    unittest.main()
