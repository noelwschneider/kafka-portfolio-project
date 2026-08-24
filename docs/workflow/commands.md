# Commands

Reference for the workflow in `user-guide.md`.

## This project's skills

| Command | What it does | When |
|---|---|---|
| `/sprint-plan` | Reviews the backlog and proposes a candidate slate with reasoning (Sonnet/high) | Starting sprint planning |
| `/sprint-open` | Creates the plan doc, updates the index, sets up the board (Sonnet/medium) | Once the slate is settled |
| `/sprint-close` | Reconciles the board and corrects the record (Sonnet/high) | Sprint goals complete |
| `/sprint-review` | Captures backlog items, routes process gaps, refines the workflow (Sonnet/high) | After close, before planning next |
| `/tier` | Picks model and effort - for a subagent, or for the current session | Before delegating, or when unsure the session is tiered right |
| `/delegate` | Checklist for writing a delegation brief | Before spawning for non-trivial work |
| `/board` | Adds and updates GitHub Project board items | Work identified, started, or finished |
| `/redeploy` | Production redeploy with pre-flight checks and real verification | Deploying to the demo box |

## Built-ins worth knowing

| Command | What it does | When |
|---|---|---|
| `/tasks` | Lists background subagents and jobs; attach or stop them | Checking on delegated work |
| `/model`, `/effort` | Change the model or effort of your own session | Switching between planning and routine work |
| `/advisor` | Attaches a stronger model that gets consulted at decision points | Long tasks where plan quality decides the outcome |
| `/subtask` | Spawns a subagent that inherits your full conversation | A helper that would need too much re-briefing otherwise |
| `/context` | Shows what is consuming your context window | The session feels sluggish or forgetful |
| `/compact` | Replaces history with a summary | Context is full but the thread matters |
| `/clear` | Starts fresh, keeping the session resumable | Switching to unrelated work |
| `/rewind` | Restores files and conversation to an earlier point | An edit went wrong |
| `/resume`, `/branch` | Returns to a past conversation, or forks the current one | Picking work back up, or trying a different approach |
| `/memory` | Views and edits what Claude has remembered about you | Something learned is now wrong |
| `/code-review` | Reviews the current diff for bugs and cleanups | Before landing a substantial change |
| `/security-review` | Security review of pending changes | Touching auth, secrets, or exposed surfaces |
| `/export` | Saves the conversation as readable text | Keeping a record of a decision-heavy session |

## From the terminal

| Command | What it does |
|---|---|
| `claude agents` | One screen showing every background session and which need input |
| `claude --bg "<task>"` | Dispatches a background session from the shell |
| `claude --agent <preset>` | Runs an entire session as one of the presets |
| `claude --worktree <name>` | Starts in an isolated checkout, for parallel work on the same repo |

`/config`, `/permissions`, `/doctor`, and `/hooks` open interactive panels that need a terminal
session — they aren't available in the desktop app.
