# Agents

Agents are participants, not a feature bolted to the side. This describes what
one is, what it may do, and where its writes land.

## An agent is a party

Humans and agents are the same kind of principal: a party row in the shared
system DB, identified by `(keyword "party" (str uuid))`. That is why
`@mention` works across both, why authorization needed no separate agent model,
and why an agent can be a member of a room in the ordinary sense.

Rooms come from [dvergr](https://github.com/replikativ/dvergr) — it owns the
discourse model, the turn factory, the per-room scheduler and the telegram
channel. simmis adds policy on top: which personas exist, what prompt they get,
which tools they are handed. That policy lives in `agents/room_agents.clj`, and
the personas and the tool manual in `agents/templates.clj`.

## The sandbox

An agent's code runs in an [SCI](https://github.com/babashka/sci) context with
a curated vocabulary, injected as namespaces:

| Namespace | For |
|---|---|
| `wiki` / `kb` | pages, blocks, attributes, typed properties |
| `kontor` | the room's double-entry book — `entry!`, `balances`, `accounts` |
| `proposal` | opening, filing and releasing proposals |
| `sheet` / `office` | spreadsheet and document intake |
| `screen` | screen captures the room has been granted |
| a shell | over a [muschel](https://github.com/replikativ/muschel) mount, so file tools and the shell see one filesystem |

Plus ordinary `datahike.api`, so an agent can query rather than being restricted
to a hand-built accessor for every question.

The vocabulary carries `:doc` and `:arglists` (`agents/vocab.clj`) so `doc`
works inside the sandbox. This matters more than it sounds: a capability an
agent cannot discover is a capability it does not have, and the failure looks
like the agent being unable to do something rather than being unable to find
it.

**The sandbox is a soft boundary.** It is a curated SCI context hardened
against the escapes we have found, not a VM. Do not run untrusted agents
against secrets you cannot rotate.

## Writes land on forks

A governed write from an agent does not go to trunk. It goes to a **fork** — a
[yggdrasil](https://github.com/replikativ/yggdrasil) branch — and a *proposal*
collects forks across KBs, the book and the room's code repository. Someone
with `:merge` authority accepts it, and only then does it land.

Dvergr Runs use the same rule at a wider boundary. A Run may execute in an
isolated subworld containing several forked systems. The Run records the
execution; it does not automatically become a Proposal. When the agent or user
explicitly files substantive retained work, Simmis adopts the world, partitions
its systems into independently governed Proposal components, and links the
Proposal back to the exact Run. This keeps execution isolation, review and
governance distinct while preserving their causal relationship.

This is the control that lets you be generous about what agents may write. The
axis is not read versus write; it is where the write lands. See
[proposals-and-time-travel.md](proposals-and-time-travel.md) for the mechanism
and [authority.md](authority.md) for who may accept.

Two consequences worth knowing when reading the code:

- A **read** must not mint a fork. Tools are split so that pure readers get an
  existing fork if one is open and never create one; only write-capable tools
  mint.
- Once a proposal is **filed**, the agent's tools are pointed at a filesystem
  that refuses writes — otherwise a write after filing lands on trunk, outside
  the review the proposal exists to provide.

## Egress is a capability, not a review

Review cannot cover irreversible actions: by the time you are reviewing, the
mail has been sent. Egress — email, telegram, HTTP, money, tokens — is
authorized in advance with a budget rather than after the fact.

## Models

Agents call an LLM through a provider key from the environment —
`FIREWORKS_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`. A provider is
registered only when its key is present. `OPENAI_API_KEY` on its own talks to
OpenAI; `OPENAI_BASE_URL` re-points that key at any other OpenAI-compatible
endpoint, including a local one. Each agent's model can be set per room in its
settings. Fireworks always uses its own base and `FIREWORKS_API_KEY`: provider
credentials are never borrowed, even when two provider records have the same
URL. Supplying `OPENAI_BASE_URL` also marks that record OpenAI-compatible rather
than native OpenAI, so provider-specific request behavior remains explicit.

An agent stores a model FAMILY (`gpt-*-luna`, `accounts/fireworks/models/glm-*`)
and either a pinned version or `:auto`; the concrete id is resolved on every
turn against `/models`, which answers which model ids that credential can
currently reach (`is.simm.model.model-selection`). It does not supply pricing,
context limits or capability metadata. Each returned id retains its provider,
base URL, credential source and reachability; identical URLs therefore remain
two records. If one fetch fails, only that provider's last-known ids survive,
marked unreachable and omitted from availability-driven picker rows. An agent
that stores neither follows its OWNER's preference from Settings, then the code
default.

A model must also exist in dvergr's registry, which is where its context window
capabilities and price per token come from — an unregistered id fails the turn
rather than running at an unknown cost. `:auto` therefore picks the newest
version that the provider serves AND the registry knows: Fireworks was serving
kimi-k3 while the registry stopped at k2p6, and one version behind beats a turn
that cannot start. No third-party metadata refresh runs in the first agent turn;
dvergr's registry is the metadata source from process start.

The PROVIDER is derived from the model, never stored beside it. A stored
provider is a second thing to keep in sync, and it used to win: every agent
created in the UI was stamped `:fireworks` at creation, so pinning one to an
OpenAI model still posted the request to Fireworks. `room-agents/describe-model`
resolves model, provider and version together, and both the agent inspector and
the room settings render exactly what it returns, so no screen can disagree with
a turn.

One native-provider quirk is load-bearing here: on OpenAI's configured
`/v1/chat/completions` path, the GPT-5.6 entries require `reasoning_effort`
`"none"` when tools are attached. dvergr sends that only to native OpenAI, so
tools work and server-side reasoning does not; an arbitrary compatible endpoint
does not receive an OpenAI-native field. Agents that need both belong on
`gpt-5.5` until the Responses API is spoken.
