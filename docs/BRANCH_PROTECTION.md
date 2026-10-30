# Branch protection

`main` is always deployable (from Phase 4, every merge deploys to staging), so it only changes through pull requests that pass CI. These rules enforce that with a GitHub **ruleset**. The ruleset is stored in the repository as [`.github/rulesets/main.json`](../.github/rulesets/main.json) so it is reviewed like code.

## Recommended rules for `main`

| Rule | Setting | Why |
| --- | --- | --- |
| Restrict deletions | On | `main` can never be deleted |
| Block force pushes | On | History on `main` is never rewritten |
| Require linear history | On | Matches squash merging: one commit per pull request |
| Require a pull request before merging | On, **0** approvals | Nothing lands without a PR. This is a solo project and GitHub does not let authors approve their own PRs, so requiring an approval would block every merge. Raise it to 1 if collaborators join. |
| Allowed merge methods | Squash only | One Conventional Commit per PR keeps `main` readable and makes release notes easy |
| Require conversation resolution | On | Review comments must be resolved, not ignored |
| Dismiss stale approvals on push | On | An approval applies to the code that was reviewed |
| Require status checks | **`CI result`**, branch must be up to date | One gate job that fails if any CI job fails. Jobs skipped by path filters count as passing, so docs-only PRs are not blocked. |
| Bypass list | Empty | Rules apply to the owner too; emergencies go through a PR like everything else |

Repository settings that go with these (already set):

- Squash merging only (merge commits and rebase merging off)
- Automatically delete head branches after merge

Recommended extra settings (Settings → Code security):

- Dependabot alerts and security updates: on
- Secret scanning and push protection: on (free for public repositories)
- Private vulnerability reporting: on, so [SECURITY.md](../SECURITY.md) works

## Apply or update the ruleset

Needs the GitHub CLI signed in as a repository admin.

```bash
gh api repos/EthanYap123/slotted/rulesets --method POST --input .github/rulesets/main.json
```

To update it later, find its ID and send the file again:

```bash
gh api repos/EthanYap123/slotted/rulesets --jq '.[] | "\(.id) \(.name)"'
```

```bash
gh api repos/EthanYap123/slotted/rulesets/RULESET_ID --method PUT --input .github/rulesets/main.json
```

The same rules can be set by hand under **Settings → Rules → Rulesets → New branch ruleset**.

## If a check name changes

The required check is the job **name** `CI result` in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). Renaming that job without updating the ruleset blocks every pull request, because the required check never reports. Change both in the same pull request, and update the ruleset straight after merging.
