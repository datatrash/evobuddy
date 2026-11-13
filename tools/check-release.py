#!/usr/bin/env python3
"""Check release versions, listing assets and the static site's local links."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, unquote
import argparse
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.ids = set()

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if "id" in attrs:
            assert attrs["id"] not in self.ids, f"Duplicate id: {attrs['id']}"
            self.ids.add(attrs["id"])
        for name in ("href", "src"):
            if name in attrs:
                self.links.append(attrs[name])
        if tag == "img":
            assert "alt" in attrs, "Image needs alt text"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-tag", action="store_true")
    parser.add_argument("--expected-tag")
    args = parser.parse_args()
    build = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([^"]+)"', build)[1]
    code = re.search(r"versionCode = (\d+)", build)[1]
    recipe = (ROOT / "fdroid/metadata/sh.datatra.evobuddy.yml").read_text()
    for expected in (f"versionName: {version}", f"versionCode: {code}", f"CurrentVersion: {version}", f"CurrentVersionCode: {code}"):
        assert expected in recipe, f"Metadata mismatch: {expected}"
    pinned_commit = re.search(r"^    commit: ([a-f0-9]{40})$", recipe, re.MULTILINE)
    assert pinned_commit, "F-Droid build must pin a full commit hash"
    assert re.search(r"^AuthorName: .+", recipe, re.MULTILINE), "Missing F-Droid author"
    assert "License: Apache-2.0" in recipe
    assert "NonFreeNet:" in recipe and "TetheredNet:" in recipe
    assert "Version 2.0, January 2004" in (ROOT / "LICENSE").read_text()
    assert 'signingConfig' not in build.split('release {', 1)[1].split('create("preview")', 1)[0], "Release must remain unsigned"
    for locale in (ROOT / "fastlane/metadata/android").iterdir():
        title = (locale / "title.txt").read_text().strip()
        summary = (locale / "short_description.txt").read_text().strip()
        description = (locale / "full_description.txt").read_text().strip()
        changes = (locale / "changelogs" / f"{code}.txt").read_text().strip()
        assert 0 < len(title) <= 50
        assert 0 < len(summary) <= 80 and not summary.endswith(".")
        assert 0 < len(description) <= 4000
        assert 0 < len(changes) <= 500
    images = ROOT / "fastlane/metadata/android/en-US/images"
    for name in ("icon.png", "featureGraphic.png", "phoneScreenshots/1.png", "phoneScreenshots/2.png", "phoneScreenshots/3.png"):
        assert (images / name).is_file(), f"Missing listing image: {name}"
    site = ROOT / "site"
    for page in site.glob("*.html"):
        document = Links()
        document.feed(page.read_text())
        for link in document.links:
            parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc:
                assert parsed.scheme in ("https", "mailto"), f"Unexpected external URL: {link}"
                continue
            target = (site / unquote(parsed.path.lstrip("/"))) if parsed.path.startswith("/") else page.parent / unquote(parsed.path)
            if not parsed.path or target.is_dir():
                target = target / "index.html" if parsed.path else page
            assert target.is_file(), f"Broken local link in {page.name}: {link}"
            if parsed.fragment and target.suffix == ".html":
                destination = Links()
                destination.feed(target.read_text())
                assert parsed.fragment in destination.ids, f"Missing anchor: {link}"
    config = tomllib.loads((ROOT / "netlify.toml").read_text())
    assert config["build"]["publish"] == "site"
    assert not config["build"].get("command"), "Static site should not require a build"
    ET.parse(site / "sitemap.xml")
    if args.expected_tag:
        assert args.expected_tag == f"v{version}", "Tag does not match the app version"
        args.require_tag = True
    if args.require_tag:
        subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/v{version}"], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        tagged = subprocess.check_output(["git", "rev-parse", f"v{version}^{{commit}}"], cwd=ROOT, text=True).strip()
        if args.expected_tag:
            current = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
            assert tagged == current, "Build checkout does not match the release tag"
        else:
            assert tagged == pinned_commit[1], "F-Droid commit does not match the release tag"
    print(f"Release {version} ({code}): metadata, artwork and local site links checked")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
