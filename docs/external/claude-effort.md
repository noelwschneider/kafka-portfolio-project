# How effort works
By default, Claude uses high effort, spending as many tokens as needed for excellent results. You can raise the effort level to max for the absolute highest capability, or lower it to be more conservative with token usage, optimizing for speed and cost while accepting some reduction in capability.

> Setting effort to "high" produces exactly the same behavior as omitting the effort parameter entirely.

The effort parameter affects all tokens in the response, including:
- Text responses and explanations
- Tool calls and function arguments
- Thinking (when active)

This approach has two major advantages:
1. It doesn't require thinking to be enabled.
2. It can affect all token spend including tool calls. For example, lower effort would mean Claude makes fewer tool calls. This gives a much greater degree of control over efficiency.

# Effort levels
| Level | Description | Typical use case |
| ----- | ----------- | ---------------- |
| max | Absolute maximum capability with no constraints on token spending. Available on Claude Fable 5, Claude Mythos 5, Claude Opus 5, Claude Opus 4.8, Claude Mythos Preview, Claude Opus 4.7, Claude Opus 4.6, Claude Sonnet 5, and Claude Sonnet 4.6. | Tasks requiring the deepest possible reasoning and most thorough analysis |
| xhigh | Extended capability for long-horizon work. Available on Claude Fable 5, Claude Mythos 5, Claude Opus 5, Claude Opus 4.8, Claude Opus 4.7, and Claude Sonnet 5. | Long-running agentic and coding tasks (over 30 minutes) with token budgets in the millions |
| high | High capability. Equivalent to not setting the parameter. | Complex reasoning, difficult coding problems, agentic tasks |
| medium | Balanced approach with moderate token savings. | Agentic tasks that require a balance of speed, cost, and performance |
| low | Most efficient. Significant token savings with some capability reduction. | Simpler tasks that need the best speed and lowest costs, such as subagents |

xhigh is a newer level; some models that support max don't support xhigh.

> Effort is a behavioral signal, not a strict token budget. At lower effort levels, Claude will still think on sufficiently difficult problems, but it will think less than it would at higher effort levels for the same problem.

# Recommended effort levels for Claude Sonnet 5
Claude Sonnet 5 defaults to high effort on the Claude API and Claude Code.

- High effort (default): Suitable for complex reasoning, coding, and agentic tasks where quality matters more than speed or cost.
- Xhigh effort: For the hardest coding and agentic tasks. See Prompting Claude Sonnet 5.
- Medium effort: Cost-saving step-down from the default. Comparable to Claude Sonnet 4.6 at high effort.
- Low effort: For high-volume or latency-sensitive workloads. Suitable for chat and non-coding use cases where faster turnaround is prioritized.
- Max effort: For tasks requiring the absolute highest capability with no constraints on token spending.

# Recommended effort levels for Claude Sonnet 4.6
Sonnet 4.6 defaults to high effort. Explicitly set effort when using Sonnet 4.6 to avoid unexpected latency:

- Medium effort (recommended default): Best balance of speed, cost, and performance for most applications. Suitable for agentic coding, tool-heavy workflows, and code generation.
- Low effort: For high-volume or latency-sensitive workloads. Suitable for chat and non-coding use cases where faster turnaround is prioritized.
- High effort: For complex reasoning and tasks where quality matters more than speed or cost.
- Max effort: For tasks requiring the absolute highest capability with no constraints on token spending.

# Recommended effort levels for Claude Opus 4.7
Start with xhigh for coding and agentic use cases, and use high as the minimum for most intelligence-sensitive workloads. Step down to medium for cost-sensitive workloads, or up to max only when your evals show measurable headroom at xhigh.

The API default is high. To use xhigh, set effort explicitly; the value you pass overrides the default.

- low: Efficient, but best for short, scoped tasks. Pair low with explicit checklists if your task has multiple sections.
- medium: The drop-in for the average workflow where you want good results while reducing costs.
- high: Advanced use cases that still need a balance of intelligence and token consumption. This is often the best balance of quality and token efficiency.
- xhigh: The recommended starting point for coding and agentic work, and for exploratory tasks such as repeated tool calling, detailed web search, and knowledge-base search. Expect meaningfully higher token usage than high.
- max	Reserve for genuinely frontier problems. On most workloads max adds significant cost for relatively small quality gains, and on some structured-output or less intelligence-sensitive tasks it can lead to overthinking.

Claude Opus 4.7 also respects effort levels more strictly than Claude Opus 4.6, especially at low and medium. At lower effort levels, the model scopes its work to what was asked rather than doing more than requested. If you observe shallow reasoning on complex problems with Claude Opus 4.7, raise effort rather than prompting around it. If you must keep effort low for latency, add targeted guidance like "This task involves multistep reasoning. Think carefully before responding."

When running Claude Opus 4.7 at xhigh or max effort, set a large max_tokens so the model has room to think and act across subagents and tool calls. Starting at 64k tokens and tuning from there is a reasonable default.

# Recommended effort levels for Claude Opus 4.8
The guidance for Claude Opus 4.7 also applies to Claude Opus 4.8. Start with xhigh for coding and agentic use cases, use high for most other intelligence-sensitive workloads, and step down to medium or low only when you've measured that the lower level holds quality on your evals.

The API default is high. Set effort explicitly to use a different level; the value you pass overrides the default.

When running Claude Opus 4.8 at xhigh or max effort, set a large max_tokens so the model has room to think and act across subagents and tool calls. Starting at 64k tokens and tuning from there is a reasonable default.

# Recommended effort levels for Claude Opus 5
Claude Opus 5 supports all five effort levels. Start with high, the default, and adjust based on your evals: step up to xhigh for demanding coding and agentic work, or to max when a task justifies unconstrained token spending, and use low and medium liberally as your primary control for token cost and response time wherever your evals show quality holds. If you carried effort settings over from an earlier model, run a fresh effort sweep on your evals rather than reusing them.

Effort controls thinking volume, not visible response length: on Claude Opus 5, changing effort does not reliably shorten responses, so prompt for length instead.

The API default is high. Set effort explicitly to use a different level; the value you pass overrides the default.

On Claude Opus 5, thinking cannot be disabled at xhigh or max effort: requests that set thinking: {"type": "disabled"} at those levels return a 400 error. See Effort with thinking.

When running Claude Opus 5 at xhigh or max effort, set a large max_tokens so the model has room to think and act across subagents and tool calls. Starting at 64k tokens and tuning from there is a reasonable default.
