# Contributing

Issues and pull requests are welcome. This file is short on purpose: it covers
the things that are not obvious from reading the code, and links to the places
that explain the rest.

## Getting it running

```bash
npm install
clj -M:dev        # Maven only — needs nothing but this repo
```

Then copy `config.example.edn` to `config.local.edn` and set an account under
`:alpha-users`; there is no self-registration. `config.example.edn` documents
every environment variable simmis reads, so it is worth a read before the
README.

Use `clj -M:dev` unless you are changing a sibling library. `:stack` and
`:local` expect sibling checkouts (`../dvergr`, `../spindel`, …) and inherit
whatever branch each one happens to sit on, which produces boot failures that
point at simmis and are not simmis's fault.

## Before you open a PR

```bash
clojure -X:test                    # note -X; -M:test silently opens a REPL
npx shadow-cljs -A:shadow release app
```

CI runs both. The ClojureScript build matters more than it looks: the test
runner executes nothing in `.cljs`, so a broken require or an undeclared var in
the UI reaches a browser and nowhere else.

## Two things the code enforces that reviewers cannot

**Every HTTP route declares who may reach it.** Routes carry an `:auth` key —
`:public`, `:authenticated`, or `{:action … :resource …}` — and
`http-auth/validate-auth-declared!` runs as reitit's `:validate` hook, so a
route without one stops the server from starting and names itself. If your
server will not boot and the error mentions `:http/undeclared-routes`, that is
this. Decide what the route needs; do not reach for `:public` to make the
message go away.

**Every RPC has a policy row.** `defn-spin-remote` registrations are joined
against `access/rpc-policy` by `rpc-coverage-test`. A missing row means the RPC
is refused at runtime — deny-by-default — so the symptom is a dead feature
rather than an open one, and the test is what tells you which.

Both tables use the same vocabulary and the same `can?` predicate. There is one
authorization seam; please do not add a second.

## UI code

simmis is the reference application for [spindel](https://github.com/replikativ/spindel),
which is FRP signals and incremental DOM — **not React**. Read the "spindel
sharp edges" section of [`CLAUDE.md`](CLAUDE.md) before changing anything under
`uis/`. Most UI bugs shipped here came from breaking one of those four rules,
and none of them fail loudly.

Remote calls from the client go through `uis/web/desktop/remote.cljc`
(`invoke!`, `spin!`, `report-error!`), which surfaces failures to the user by
default. Silence is opt-in with `:silent?`, and it should be rare: a call the
user initiated is not a candidate.

## Style

- Server-side logging is Telemere only — no `println`/`prn` in committed code.
- Comments should say *why*, especially where the obvious thing is wrong. There
  is a lot of that here, and it is deliberate.
- If you fix a bug, add the test that fails without the fix, and check that it
  does fail. A guard test that has never been seen to fail is a guess.

## Reporting a security issue

Please do not open a public issue for anything that looks exploitable — use
GitHub's private vulnerability reporting on this repository instead.

Check [Known gaps](README.md#known-gaps) first: it lists what is already known
to be open, notably that blob reads are authenticated but not authorized —
any signed-in user can fetch any blob by hash. Those are documented
rather than hidden, so a report about one of them tells us nothing new.
