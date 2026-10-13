#!/usr/bin/env python3
"""Assemble independently authored unit batches before the separate Atlas packing command.

This only combines source metadata. It cannot certify art or promote candidate coverage.
"""
import argparse
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def encoded(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def clip_id(clip):
    return clip["unitName"], clip["state"], clip["facing"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batch", type=Path, nargs="+", help="Additive authoring manifest(s)")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    manifest_path = root / "art/atlas/manifest.json"
    coverage_path = root / "art/atlas/coverage.json"
    provenance_path = root / "art/atlas/provenance.json"
    manifest, coverage, provenance = map(read, (manifest_path, coverage_path, provenance_path))
    expected_names = set(coverage["requiredUnitNames"])
    units = {unit["unitName"]: unit for unit in manifest["units"]}
    clips = {clip_id(clip): clip for clip in manifest["clips"]}
    rows = {row["id"]: row for row in coverage["rows"]}
    entries = {entry["assetId"]: entry for entry in provenance["entries"]}
    assigned_names = set()

    for raw_path in args.batch:
        path = raw_path if raw_path.is_absolute() else root / raw_path
        path = path.resolve()
        if not path.is_relative_to(root / "art/atlas/units"):
            raise ValueError("Batch manifests must be tracked beneath art/atlas/units")
        batch = read(path)
        if batch["schemaVersion"] != manifest["schemaVersion"]:
            raise ValueError(f"Schema mismatch: {path}")
        for contract in ("packing", "budgets"):
            if contract in batch and batch[contract] != manifest[contract]:
                raise ValueError(f"Batch changes the {contract} contract: {path}")
        names = {unit["unitName"] for unit in batch["units"]}
        if not names or not names <= expected_names or names & assigned_names:
            raise ValueError(f"Empty, unknown or overlapping unit ownership: {path}")
        assigned_names |= names
        if len(names) != len(batch["units"]):
            raise ValueError(f"Duplicate unit identity: {path}")
        if any(clip["unitName"] not in names for clip in batch["clips"]):
            raise ValueError(f"Clip outside the batch's unit ownership: {path}")
        if len({clip_id(clip) for clip in batch["clips"]}) != len(batch["clips"]):
            raise ValueError(f"Duplicate clip identity: {path}")
        # Replacing an owned unit replaces its clips too, so old state/facing entries cannot linger.
        clips = {key: clip for key, clip in clips.items() if key[0] not in names}
        units.update((unit["unitName"], unit) for unit in batch["units"])
        clips.update((clip_id(clip), clip) for clip in batch["clips"])

        prefix = path.name.removesuffix("-manifest.json")
        if prefix == path.name:
            raise ValueError("Use a batch-name-manifest.json filename")
        batch_coverage = read(path.with_name(prefix + "-coverage-candidates.json"))
        for row in batch_coverage["rows"]:
            if row["id"] not in rows or row["category"] not in ("unit-static", "unit-clip", "embarked"):
                raise ValueError(f"Unknown or non-unit coverage row: {row['id']}")
            if row.get("rulesetKey") not in names:
                raise ValueError(f"Coverage outside the batch's ownership: {row['id']}")
            if row["reviewState"] != "candidate":
                raise ValueError("Assembly only accepts candidate source evidence; acceptance is a later step")
            rows[row["id"]] = row
        batch_provenance = read(path.with_name(prefix + "-provenance.json"))
        asset_ids = {unit["assetId"] for unit in batch["units"]}
        for entry in batch_provenance["entries"]:
            if entry["assetId"] not in asset_ids:
                raise ValueError(f"Provenance outside the batch's ownership: {entry['assetId']}")
            entries[entry["assetId"]] = entry

    manifest["units"] = sorted(units.values(), key=lambda item: item["unitName"])
    manifest["clips"] = [clips[key] for key in sorted(clips)]
    coverage["rows"] = [rows[row["id"]] for row in coverage["rows"]]
    coverage["status"] = "Original Atlas art candidates assembled; acceptance remains deferred"
    provenance["entries"] = sorted(entries.values(), key=lambda item: item["assetId"])
    # Parse every batch before writing any shared file. Packing reads this assembled source next.
    outputs = [(manifest_path, encoded(manifest)), (coverage_path, encoded(coverage)),
               (provenance_path, encoded(provenance))]
    for path, value in outputs:
        pending = path.with_suffix(path.suffix + ".assembling")
        pending.write_text(value, encoding="utf-8")
        pending.replace(path)
    print(f"Assembled {len(assigned_names)} batch units: {len(units)} total units / {len(clips)} source clips.")
    print("Run scripts/atlas/assets.sh pack to rebuild runtime metadata/pages; static atlas packing is separate.")


if __name__ == "__main__":
    main()
