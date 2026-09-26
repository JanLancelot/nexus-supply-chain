"""Check dashboard wiring without Docker; promtool and real services run separately."""

import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
GRAFANA = ROOT / "docker" / "grafana"


class MonitoringDashboardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dashboards = {
            path.name: json.loads(path.read_text(encoding="utf-8"))
            for path in (GRAFANA / "dashboards").glob("*.json")
        }
        provisioned = (GRAFANA / "provisioning/datasources/datasource.yml").read_text(encoding="utf-8")
        cls.datasource_uids = set(re.findall(r"^\s+uid:\s*([\w-]+)\s*$", provisioned, re.MULTILINE))

    def test_dashboards_have_unique_stable_identities_and_no_panel_overlap(self):
        self.assertTrue(self.dashboards, "The provisioned dashboard directory is empty")
        uids = []
        for filename, dashboard in self.dashboards.items():
            with self.subTest(dashboard=filename):
                uids.append(dashboard["uid"])
                self.assertIsNone(dashboard["id"], "Database-specific IDs cannot be provisioned portably")
                panels = dashboard["panels"]
                self.assertTrue(panels)
                self.assertEqual(len(panels), len({panel["id"] for panel in panels}))
                occupied = set()
                for panel in panels:
                    grid = panel["gridPos"]
                    self.assertGreater(grid["w"], 0)
                    self.assertGreater(grid["h"], 0)
                    self.assertGreaterEqual(grid["x"], 0)
                    self.assertGreaterEqual(grid["y"], 0)
                    self.assertLessEqual(grid["x"] + grid["w"], 24)
                    cells = {
                        (x, y)
                        for x in range(grid["x"], grid["x"] + grid["w"])
                        for y in range(grid["y"], grid["y"] + grid["h"])
                    }
                    self.assertFalse(occupied & cells, f"Overlapping panel: {panel['title']}")
                    occupied |= cells
        self.assertEqual(len(uids), len(set(uids)))

    def test_every_query_references_a_provisioned_datasource_and_defined_variables(self):
        self.assertTrue(self.datasource_uids)
        for filename, dashboard in self.dashboards.items():
            variables = {variable["name"] for variable in dashboard["templating"]["list"]}
            variables |= {"__rate_interval", "__interval", "__range", "__range_s"}
            for panel in dashboard["panels"]:
                if panel["type"] == "text":
                    continue
                with self.subTest(dashboard=filename, panel=panel["title"]):
                    self.assertIn(panel["datasource"]["uid"], self.datasource_uids)
                    self.assertTrue(panel["targets"])
                    refs = set()
                    for target in panel["targets"]:
                        self.assertIn(target["datasource"]["uid"], self.datasource_uids)
                        self.assertNotIn(target["refId"], refs)
                        refs.add(target["refId"])
                        self.assertTrue(target["expr"].strip())
                        used = set(re.findall(r"\$(?:\{)?([a-zA-Z_]\w*)", target["expr"]))
                        self.assertFalse(used - variables, f"Undefined variables: {used - variables}")

    def test_business_dashboards_expose_freshness_without_turning_missing_counts_into_zero(self):
        for filename, dashboard in self.dashboards.items():
            expressions = [target["expr"] for panel in dashboard["panels"] for target in panel.get("targets", [])]
            business = [expression for expression in expressions if re.search(r"nexus_(?:inventory|orders)", expression)]
            if not business:
                continue
            with self.subTest(dashboard=filename):
                self.assertTrue(any("nexus_observability_last_success_timestamp_seconds" in expression for expression in expressions))
                self.assertTrue(any("nexus_observability_refresh_success" in expression for expression in expressions))
                for expression in business:
                    self.assertNotRegex(expression, r"\bor\s+(?:vector\s*\()?0", "Missing instrumentation is not an empty inventory")

    def test_environment_selection_scopes_instances_and_business_aggregates(self):
        for filename, dashboard in self.dashboards.items():
            with self.subTest(dashboard=filename):
                variables = {variable["name"]: variable for variable in dashboard["templating"]["list"]}
                self.assertFalse(variables["environment"]["multi"])
                self.assertFalse(variables["environment"]["includeAll"])
                if "instance" in variables:
                    self.assertIn('$environment', variables["instance"]["definition"])
                    # Scrape metrics without environment labels need scoped instances.
                    self.assertFalse(variables["instance"].get("allValue"))
                else:
                    # Service objectives aggregate all replicas. Every query must
                    # instead select its environment directly, including alert tables.
                    for panel in dashboard["panels"]:
                        for target in panel.get("targets", []):
                            self.assertRegex(target["expr"], r'environment=~?"\$environment"')
                for panel in dashboard["panels"]:
                    for target in panel.get("targets", []):
                        if re.search(r"nexus_(?:inventory|orders)", target["expr"]):
                            self.assertIn('environment=~"$environment"', target["expr"])

    def test_optional_alertmanager_discovery_has_valid_target_groups(self):
        targets = json.loads((ROOT / "docker/prometheus/alertmanagers.json").read_text(encoding="utf-8"))
        self.assertIsInstance(targets, list)
        for group in targets:
            self.assertIsInstance(group["targets"], list)
            self.assertTrue(group["targets"])
            self.assertTrue(all(isinstance(target, str) and target for target in group["targets"]))


if __name__ == "__main__":
    unittest.main()
