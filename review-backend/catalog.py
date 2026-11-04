"""Static synthetic catalogue for the review sandbox. Plain data only: no imports from Hermes, no I/O."""
import re

EFFORTS = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra']
DEFAULT_MODEL = {'model': 'synthetic-demo', 'provider': 'sandbox'}
# The "large" model asks for a cost confirmation, so reviewers can see that dialog without a real provider.
CONFIRM_MODELS = {'synthetic-large'}

MODELS = {
    'providers': [
        {'slug': 'sandbox', 'name': 'Sandbox (synthetic)', 'source': 'built-in', 'is_current': True,
         'models': ['synthetic-demo', 'synthetic-fast', 'synthetic-large'],
         'capabilities': {'synthetic-demo': {'fast': True, 'reasoning': True},
                          'synthetic-fast': {'fast': True, 'reasoning': False},
                          'synthetic-large': {'fast': False, 'reasoning': True}}},
        {'slug': 'example', 'name': 'Example provider (synthetic)', 'source': 'built-in', 'is_current': False,
         'models': ['example-mini', 'example-pro'],
         'capabilities': {'example-mini': {'fast': False, 'reasoning': True},
                          'example-pro': {'fast': False, 'reasoning': True}}},
    ],
    'efforts': EFFORTS,
}
for _p in MODELS['providers']:
    _p['total_models'] = len(_p['models'])


def model_caps(provider, model):
    for p in MODELS['providers']:
        if p['slug'] == provider:
            return dict(p['capabilities'].get(model) or {'fast': False, 'reasoning': True})
    return {'fast': False, 'reasoning': True}


def known_model(provider, model):
    return any(p['slug'] == provider and model in p['models'] for p in MODELS['providers'])


def _f(key, label, help='', type=None, options=None, restart=False, unit='', min=None, max=None):
    d = {'key': key, 'label': label, 'help': help}
    if type:
        d['type'] = type
    if options is not None:
        d['options'] = options
    if restart:
        d['restart'] = True
    if unit:
        d['unit'] = unit
    if min is not None:
        d['min'] = min
    if max is not None:
        d['max'] = max
    return d


# Mirrors the production curated groups (server/mobile/control.py GROUPS); a test keeps the keys in step.
GROUPS = [
    {'title': 'Model', 'tab': 'Model', 'fields': [
        _f('model.default', 'Default model', 'New chats start on this. A chat can override it from the composer.', 'model'),
        _f('agent.reasoning_effort', 'Reasoning effort', 'Default thinking budget. Per-model overrides still win.', 'select', ['', *EFFORTS]),
        _f('agent.service_tier', 'Fast mode', 'Priority processing where the provider supports it.', 'select', ['', 'normal', 'fast', 'auto', 'cold']),
        _f('display.personality', 'Personality', 'Tone layered on top of the system prompt.', 'select', ['none', 'concise', 'friendly', 'technical']),
        _f('agent.max_turns', 'Max tool turns', 'Tool-calling iterations per message before {agent} stops.', 'number', min=1, max=1000),
    ]},
    {'title': 'Approvals & safety', 'tab': 'Safety', 'fields': [
        _f('approvals.mode', 'Approval mode', 'manual asks for every flagged command, smart lets a reviewer clear safe ones, off never asks.', 'select', ['manual', 'smart', 'off']),
        _f('approvals.timeout', 'Approval timeout', 'How long a request waits for you.', 'number', unit='s', min=5, max=3600),
        _f('approvals.cron_mode', 'Cron approvals', 'Cron runs have nobody to ask: deny or approve flagged commands.', 'select', ['deny', 'approve']),
        _f('security.redact_secrets', 'Redact secrets', 'Mask keys and tokens in tool output and logs.', 'bool'),
        _f('skills.write_approval', 'Approve skill edits', 'Ask before {agent} creates or edits a skill.', 'bool'),
        _f('memory.write_approval', 'Approve memory edits', 'Ask before {agent} writes to memory.', 'bool'),
    ]},
    {'title': 'Memory & context', 'tab': 'Memory', 'fields': [
        _f('memory.memory_enabled', 'Memory', "{agent}'s own notes, injected every session.", 'bool'),
        _f('memory.user_profile_enabled', 'User profile', 'What {agent} knows about you.', 'bool'),
        _f('compression.enabled', 'Auto-compress', 'Summarize old turns when the context fills.', 'bool'),
        _f('compression.threshold', 'Compress at', 'Fraction of the context window that triggers it.', 'number', min=0.1, max=0.95),
        _f('curator.enabled', 'Skill curator', 'Periodic review that archives stale skills.', 'bool'),
    ]},
    {'title': 'Subagents', 'tab': 'Model', 'fields': [
        _f('delegation.reasoning_effort', 'Subagent reasoning', 'Thinking budget for delegated tasks.', 'select', ['', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra']),
        _f('delegation.max_concurrent_children', 'Parallel subagents', '', 'number', min=1, max=32),
        _f('delegation.max_iterations', 'Subagent turn limit', '', 'number', min=1, max=2000),
    ]},
    {'title': 'Tools', 'tab': 'Tools', 'fields': [
        _f('terminal.backend', 'Terminal backend', 'Where commands run.', 'select', ['local', 'docker', 'ssh', 'modal', 'daytona', 'vercel_sandbox'], restart=True),
        _f('terminal.timeout', 'Command timeout', 'Foreground command limit.', 'number', unit='s', min=5, max=3600),
        _f('web.search_backend', 'Web search', '', 'text'),
        _f('browser.backend', 'Browser', '', 'text'),
        _f('code_execution.mode', 'Code execution', 'project runs in your repo, strict in a scratch dir.', 'select', ['project', 'strict']),
        _f('checkpoints.enabled', 'File checkpoints', 'Snapshot files before edits so they can be rolled back.', 'bool'),
    ]},
    {'title': 'In this app', 'tab': 'Chat', 'fields': [
        _f('display.platforms.mobile.show_reasoning', 'Show reasoning', "Prepend the model's reasoning to replies in the app.", 'bool'),
        _f('display.platforms.mobile.tool_progress', 'Tool progress bubbles', "The stock 'Running…' messages. Keep off: activity cards replace them.", 'select', ['off', 'new', 'all', 'verbose'], restart=True),
        _f('display.busy_input_mode', 'While {agent} works', "What a plain message does mid-run (the composer's Steer/Queue still win).", 'select', ['interrupt', 'queue', 'steer'], restart=True),
    ]},
    {'title': 'Voice', 'tab': 'Chat', 'fields': [
        _f('tts.provider', 'Voice provider', "Who makes {agent}'s voice for Talk and voice clips.", 'select', ['sandbox-chime', 'edge', 'elevenlabs', 'openai', 'xai']),
        _f('tts.xai.voice_id', 'xAI voice', 'Used when the provider is xai.', 'select', ['ara', 'eve', 'leo', 'rex', 'sal']),
        _f('stt.enabled', 'Transcribe voice notes', '', 'bool'),
        _f('stt.provider', 'Speech to text', '', 'select', ['local', 'groq', 'openai', 'xai', 'elevenlabs']),
    ]},
    {'title': 'System', 'tab': 'Tools', 'fields': [
        _f('timezone', 'Timezone', "Blank uses the machine's.", 'select', ['', 'UTC', 'America/New_York', 'America/Los_Angeles', 'Europe/London', 'Europe/Berlin', 'Asia/Jerusalem', 'Asia/Tokyo']),
        _f('logging.level', 'Log level', '', 'select', ['DEBUG', 'INFO', 'WARNING', 'ERROR'], restart=True),
    ]},
]

# Extra uncurated keys for "All settings" (category, type, help, options).
EXTRA_SETTINGS = [
    ('display.skin', 'display', 'select', 'CLI visual theme', ['default', 'ares', 'mono', 'slate']),
    ('display.resume_display', 'display', 'select', 'How resumed sessions display history', ['minimal', 'full', 'off']),
    ('display.compact', 'display', 'bool', 'Compact CLI output', None),
    ('context.engine', 'context', 'select', 'Context management engine', ['default', 'custom']),
    ('human_delay.mode', 'general', 'select', 'Simulated typing delay mode', ['off', 'typing', 'fixed']),
    ('stt.local.model', 'stt', 'select', 'Local transcription model size', ['tiny', 'base', 'small', 'medium']),
    ('memory.memory_char_limit', 'memory', 'number', 'Character budget for agent notes', None),
    ('memory.user_char_limit', 'memory', 'number', 'Character budget for the user profile', None),
    ('cron.max_parallel', 'cron', 'number', 'Cron jobs that may run at once', None),
    ('web.max_results', 'web', 'number', 'Search results per query', None),
    ('checkpoints.max_snapshots', 'checkpoints', 'number', 'Snapshots kept per session', None),
    ('dashboard.theme', 'dashboard', 'select', 'Web dashboard visual theme', ['default', 'midnight', 'ember', 'mono']),
]

DEFAULT_SETTINGS = {
    'agent.reasoning_effort': 'medium', 'agent.service_tier': 'normal', 'display.personality': 'none', 'agent.max_turns': 90,
    'approvals.mode': 'manual', 'approvals.timeout': 300, 'approvals.cron_mode': 'deny', 'security.redact_secrets': True,
    'skills.write_approval': True, 'memory.write_approval': False, 'memory.memory_enabled': True,
    'memory.user_profile_enabled': True, 'compression.enabled': True, 'compression.threshold': 0.8, 'curator.enabled': False,
    'delegation.reasoning_effort': '', 'delegation.max_concurrent_children': 3, 'delegation.max_iterations': 200,
    'terminal.backend': 'local', 'terminal.timeout': 180, 'web.search_backend': 'synthetic', 'browser.backend': 'none',
    'code_execution.mode': 'strict', 'checkpoints.enabled': True, 'display.platforms.mobile.show_reasoning': False,
    'display.platforms.mobile.tool_progress': 'off', 'display.busy_input_mode': 'interrupt', 'tts.provider': 'sandbox-chime',
    'tts.xai.voice_id': 'ara', 'stt.enabled': True, 'stt.provider': 'local', 'timezone': '', 'logging.level': 'INFO',
    'display.skin': 'default', 'display.resume_display': 'minimal', 'display.compact': False, 'context.engine': 'default',
    'human_delay.mode': 'off', 'stt.local.model': 'base', 'memory.memory_char_limit': 8000, 'memory.user_char_limit': 8000,
    'cron.max_parallel': 2, 'web.max_results': 8, 'checkpoints.max_snapshots': 20, 'dashboard.theme': 'default',
}

# Copied from server/mobile/control.py _GUARDED; a test compares the two so they cannot drift.
GUARDED = re.compile(r"^(approvals\.|security\.|terminal\.|hooks|code_execution\.|browser\.|mcp|plugins|delegation\.|"
                     r"tools\.|toolsets|command_allowlist|agent\.(environment|execution)|gateway\.|"
                     r"[a-z_]+\.(allowed_|allow_|extra\.allow|free_response_|dm_role_auth)|skills\.(inline_shell|trusted|write_approval)|"
                     r"memory\.write_approval|cron\.allow_agent|auth\.|dashboard\.|secrets\.|vault\.|proxy\.|"
                     r"computer_use\.|lsp\.(trusted|install)|auxiliary\.[a-z_]+\.(base_url|provider|model)$|"
                     r"auxiliary\.stream_only|model_catalog\.|models_dev\.|cron\.chronos\.)")

SKILLS = [
    {'name': 'release-checklist', 'description': 'Walk a release through review, tests and notes (synthetic example).', 'category': 'software-development', 'provenance': 'bundled', 'usage': 4},
    {'name': 'meeting-notes', 'description': 'Turn rough notes into a short summary and action list (synthetic example).', 'category': 'productivity', 'provenance': 'bundled', 'usage': 2},
    {'name': 'trip-planner', 'description': 'Draft an itinerary from dates and preferences (synthetic example).', 'category': 'productivity', 'provenance': 'agent', 'usage': 1},
    {'name': 'memory', 'description': 'Read and write the agent notes. Always on.', 'category': 'core', 'provenance': 'bundled', 'usage': 9, 'locked': True},
]

SKILL_BODY = """---
name: {name}
description: {description}
---
# {title}

**Synthetic review sandbox content.** This skill text is an example. Nothing here runs on any machine.

## Steps
1. Read the request.
2. Draft the result.
3. Ask before anything irreversible.
"""

PLUGINS = [
    {'name': 'mobile', 'key': 'mobile', 'kind': 'platform', 'version': '0.16', 'description': 'This app. Always loaded.', 'tools': 0, 'hooks': 4, 'commands': 0, 'source': 'bundled'},
    {'name': 'notes-export', 'key': 'notes-export', 'kind': 'tool', 'version': '1.2.0', 'description': 'Export chats as markdown files (synthetic example).', 'tools': 2, 'hooks': 0, 'commands': 1, 'source': 'user', 'configured': True},
    {'name': 'calendar-peek', 'key': 'calendar-peek', 'kind': 'tool', 'version': '0.4.1', 'description': 'Read-only calendar lookups (synthetic example; no account is connected).', 'tools': 1, 'hooks': 0, 'commands': 0, 'source': 'user', 'configured': False},
    {'name': 'sandbox-memory', 'key': 'memory/sandbox', 'kind': 'backend', 'version': '1.0', 'description': 'Built-in memory backend.', 'tools': 0, 'hooks': 1, 'commands': 0, 'source': 'bundled'},
]

MCP = [
    {'name': 'docs-search', 'transport': 'stdio', 'tools': 3, 'connected': True, 'disabled': False, 'status': 'connected'},
    {'name': 'weather-demo', 'transport': 'http', 'tools': 0, 'connected': False, 'disabled': True, 'status': 'disabled'},
]

COMMANDS = [
    ('retry', 'Run the last message again', '', 'Session'),
    ('new', 'Start a fresh session in this chat', '', 'Session'),
    ('undo', 'Remove the last turn from context', '', 'Session'),
    ('compress', 'Summarize old turns to free context', '', 'Session'),
    ('title', 'Rename this chat', '<title>', 'Session'),
    ('status', 'Show session status', '', 'Info'),
    ('model', 'Switch model for this chat', '<model>', 'Configuration'),
    ('reasoning', 'Set reasoning effort', '<level>', 'Configuration'),
    ('moa', 'Ask a mixture of agents', '<prompt>', 'Tools'),
    ('help', 'List commands', '', 'Info'),
    ('release-checklist', 'Walk a release through review, tests and notes', '', 'Skill'),
]

SESSIONS = [
    {'id': 's-cli-release', 'source': 'cli', 'title': 'Synthetic CLI session · release notes', 'model': 'sandbox/synthetic-demo',
     'age': 3600, 'live': True,
     'messages': [('user', 'Summarize what changed since the last release.'),
                  ('assistant', 'Synthetic summary: three example changes. Nothing was read from any repository.'),
                  ('user', 'Which one should go first in the notes?')],
     'prompts': [{'prompt_id': 'p-clarify', 'kind': 'clarify', 'question': 'Which audience are the notes for?',
                  'options': ['End users', 'Developers', 'Both']}]},
    {'id': 's-telegram-cleanup', 'source': 'telegram', 'title': 'Synthetic Telegram thread · tidy notes', 'model': 'sandbox/synthetic-fast',
     'age': 7200, 'live': False,
     'messages': [('user', 'Archive the notes older than a month.'),
                  ('assistant', 'Synthetic plan: archive 12 example notes. This needs your approval first.')],
     'prompts': [{'prompt_id': 'p-approval', 'kind': 'approval', 'command': 'demo: archive 12 example notes (not executed)',
                  'detail': 'Synthetic approval. Approving records the decision only; nothing runs.'}]},
    {'id': 's-cli-old', 'source': 'cli', 'title': 'Synthetic CLI session · superseded', 'model': 'sandbox/synthetic-demo',
     'age': 86400 * 2, 'live': False, 'read_only': True,
     'messages': [('user', 'Draft a weekly plan.'), ('assistant', 'Synthetic weekly plan. This session continued elsewhere.')],
     'prompts': []},
]

# Illustrative usage, oldest first; the last value is today. Mirrors Pulse so both screens agree.
SPEND_7D = [0.04, 0.08, 0.06, 0.11, 0.09, 0.1, 0.12]

# ---------------------------------------------------------------- Hub (synthetic marketplace)
HUB_SOURCES = [{'id': 'official', 'label': 'Official'}, {'id': 'hermes-index', 'label': 'Hermes index', 'available': True},
               {'id': 'skills-sh', 'label': 'skills.sh'}, {'id': 'github', 'label': 'GitHub'}]


def _skill(ident, name, description, source, trust, category, tags, version='1.0.0', verdict='safe', featured=False, findings=()):
    return {'identifier': ident, 'name': name, 'description': description, 'source': source, 'trust_level': trust,
            'category': category, 'tags': list(tags), 'repo': None, 'version': version, 'verdict': verdict,
            'featured': featured, 'findings': list(findings)}


HUB_SKILLS = [
    _skill('official/productivity/daily-brief', 'daily-brief', 'A short morning summary of your day (synthetic example).', 'official', 'builtin', 'productivity', ['morning', 'summary'], featured=True),
    _skill('official/writing/plain-english', 'plain-english', 'Rewrite text in short, plain sentences (synthetic example).', 'official', 'builtin', 'writing', ['editing']),
    _skill('official/software-development/changelog-writer', 'changelog-writer', 'Draft release notes from a list of changes (synthetic example).', 'official', 'builtin', 'software-development', ['release', 'notes'], version='1.1.0'),
    _skill('hermes-index/recipe-scaler', 'recipe-scaler', 'Scale a recipe up or down and convert units (synthetic example).', 'hermes-index', 'trusted', 'home', ['cooking'], featured=True),
    _skill('skills-sh/flashcards', 'flashcards', 'Turn notes into question and answer cards (synthetic example).', 'skills-sh', 'community', 'learning', ['study']),
    _skill('github/example/shell-helper', 'shell-helper', 'Suggests shell commands (synthetic example with risky patterns, so the scan blocks it).', 'github', 'community', 'tools', ['shell'],
           verdict='caution', findings=[{'severity': 'high', 'category': 'execution', 'file': 'SKILL.md', 'line': 12, 'description': 'Asks the agent to run commands without confirmation (synthetic finding).'},
                                         {'severity': 'medium', 'category': 'network', 'file': 'scripts/fetch.sh', 'line': 3, 'description': 'Downloads a script from the internet (synthetic finding).'}]),
]
HUB_SEED_INSTALLED = {'identifier': 'official/software-development/changelog-writer',
                      'entry': {'name': 'changelog-writer', 'trust_level': 'builtin', 'scan_verdict': 'safe', 'source': 'official',
                                'installed_at': '2026-10-20T09:00:00', 'updated_at': None, 'version': '1.0.0', 'enabled': True}}


def hub_skill(ident):
    return next((x for x in HUB_SKILLS if x['identifier'] == ident), None)


def hub_scan(c):
    sev = {'critical': 0, 'high': 0, 'medium': 0, 'low': 0}
    for f in c['findings']:
        sev[f['severity']] += 1
    trust, verdict = c['trust_level'], c['verdict']
    allow = trust == 'builtin' or (trust == 'trusted' and verdict != 'dangerous') or verdict == 'safe'
    reason = (f"Allowed ({trust} source, {verdict} verdict)" if allow else
              f"Blocked ({trust} source + {verdict} verdict, {len(c['findings'])} findings). Use --force to override.")
    summary = f"{c['name']}: clean scan, no threats detected" if not c['findings'] else \
        f"{c['name']}: {verdict}, {len(c['findings'])} finding(s) in " + ', '.join(sorted({f['category'] for f in c['findings']}))
    return {'verdict': verdict, 'summary': summary + ' (synthetic scan)', 'policy': 'allow' if allow else 'block',
            'policy_reason': reason, 'findings': c['findings'], 'severity_counts': sev}


def _plugin(name, title, description, category, tier, version, sha, tools=(), hooks=(), env=(), update_adds='', issues=()):
    caps = {'provides_tools': list(tools), 'provides_hooks': list(hooks), 'provides_middleware': [], 'requires_env': list(env)}
    summary = f"{name} ({tier}, maintained by Example Maintainers) {description}" + \
        (f" This plugin registers tool(s): {', '.join(tools)}" if tools else '') + (f"; hook(s): {', '.join(hooks)}." if hooks else '.')
    return {'name': name, 'title': title, 'description': description, 'maintainer': 'Example Maintainers', 'tier': tier, 'category': category,
            'requires_hermes': '>=0.16', 'subdir': None, 'docs_url': None, 'repo': None, 'version': version, 'sha': sha, 'image': None,
            'screenshots': [], 'readme': None, 'platforms': ['linux', 'macos'], 'onboarding': None, 'capabilities': caps,
            'known_issues': list(issues), 'capability_summary': summary, 'update_adds': update_adds}


HUB_PLUGINS = [
    _plugin('focus-timer', 'Focus timer', 'Start and stop focus sessions from chat (synthetic example).', 'tools', 'official', '1.4.0', 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678', tools=['focus_start', 'focus_stop']),
    _plugin('reading-list', 'Reading list', 'Save links to read later (synthetic example).', 'web', 'verified', '0.9.2', 'b2c3d4e5f60718293a4b5c6d7e8f901234567890', tools=['save_link'], env=['READING_LIST_TOKEN'],
            issues=['Synthetic known issue: titles longer than 200 characters are cut.']),
    _plugin('voice-notes', 'Voice notes', 'Keep short voice memos with the chat (synthetic example).', 'voice', 'community', '2.0.0', 'c3d4e5f60718293a4b5c6d7e8f90123456789012', tools=['memo_list'],
            hooks=['post_message'], update_adds='tools: memo_delete'),
]


def hub_plugin(name):
    return next((x for x in HUB_PLUGINS if x['name'] == name), None)


HUB_MCP = [
    {'name': 'notes-search', 'description': 'Search a folder of notes (synthetic example).', 'source': 'https://example.com/docs/notes-search',
     'transport': 'stdio', 'auth_type': 'none', 'required_env': [], 'command': 'notes-search', 'args': ['--read-only'], 'url': None,
     'install_url': None, 'bootstrap': [], 'post_install': 'Ask *"search my notes for…"* in any chat. (synthetic)', 'needs_install': False, 'tools': 2},
    {'name': 'weather-api', 'description': 'Weather forecasts through an API key (synthetic example; the key is never stored).', 'source': 'https://example.com/docs/weather-api',
     'transport': 'http', 'auth_type': 'api_key', 'required_env': [{'name': 'WEATHER_API_KEY', 'prompt': 'API key (any text works in the sandbox)', 'required': True, 'secret': True}],
     'command': None, 'args': [], 'url': 'https://example.com/mcp', 'install_url': None, 'bootstrap': [], 'post_install': 'Ask for a forecast in any chat. (synthetic)',
     'needs_install': False, 'tools': 3},
    {'name': 'calendar-sync', 'description': 'Read calendar events with OAuth (synthetic example).', 'source': 'https://example.com/docs/calendar-sync',
     'transport': 'stdio', 'auth_type': 'oauth', 'required_env': [], 'command': 'calendar-sync', 'args': [], 'url': None,
     'install_url': 'https://example.com/calendar-sync.git', 'bootstrap': ['npm ci', 'npm run build'], 'post_install': 'Sign in when the first chat asks. (synthetic)',
     'needs_install': True, 'tools': 4},
]


def hub_mcp(name):
    return next((x for x in HUB_MCP if x['name'] == name), None)


# ---------------------------------------------------------------- Wake (alarm clock)
WAKE_READINGS = [
    {'title': 'Why bread rises', 'topic': 'baking',
     'reading': 'Yeast is a tiny fungus. When it eats the sugar in dough, it makes **carbon dioxide** gas and a little alcohol.\n\n'
                'Kneading builds **gluten**, a stretchy net of proteins. The gas gets trapped in that net, so the dough puffs up.\n\n'
                'Heat in the oven makes the gas expand once more, then sets the gluten. The alcohol cooks off. '
                'That is why a loaf keeps its shape after it cools.\n\n_Synthetic reading for the review sandbox._',
     'questions': [('What does yeast eat in dough?', ['sugar']), ('Which gas makes dough rise?', ['carbon dioxide', 'co2']),
                   ('What is the stretchy protein net called?', ['gluten']), ('What builds that net?', ['knead']),
                   ('What happens to the alcohol in the oven?', ['cook', 'evapor', 'burn', 'off'])]},
    {'title': 'How bees find flowers', 'topic': 'nature',
     'reading': 'A honeybee that finds food flies home and **dances**. The waggle part of the dance points toward the food, '
                'measured from the direction of the **sun**. A longer waggle means the food is **farther** away.\n\n'
                'Other bees follow the dancer, smell the **scent** on her body, and fly out to look.\n\n_Synthetic reading for the review sandbox._',
     'questions': [('What does a bee do when it gets home?', ['danc']), ('The dance direction is measured from what?', ['sun']),
                   ('What does a longer waggle mean?', ['far']), ('What do other bees smell on the dancer?', ['scent', 'smell', 'flower']),
                   ('What do the other bees do next?', ['fly', 'look', 'search', 'find'])]},
]
WAKE_LOGIC = [('A bus leaves at 7:40 and the ride takes 35 minutes. When does it arrive? (HH:MM)', '8:15'),
              ('What is 17 + 26?', '43'), ('Which number comes next: 2, 4, 8, 16, …?', '32')]

# ---------------------------------------------------------------- Radar
RADAR_TYPES = {'tool': 'New tool', 'technique': 'Technique', 'release': 'Release', 'risk': 'Risk', 'idea': 'Idea'}
RADAR_FINDINGS = [
    {'id': 'f-sandbox-01', 'type': 'release', 'key': 'k1', 'age': 1800, 'state': 'new', 'confidence': 0.86, 'actionability': 'try today',
     'title': 'Example library 2.0 adds offline mode (synthetic)', 'why': 'Your notes app could keep working without a connection.',
     'evidence': ['Release notes: https://example.com/release-notes', 'Synthetic finding: nothing was fetched.'],
     'freshness': {'checked': 'today', 'result': 'fresh'}, 'prompt': 'Summarize what offline mode would change for the notes app.'},
    {'id': 'f-sandbox-02', 'type': 'technique', 'key': 'k2', 'age': 1800, 'state': 'new', 'confidence': 0.71, 'actionability': 'read',
     'title': 'Shorter prompts for repeated tasks (synthetic)', 'why': 'Cron jobs that repeat could cost less.',
     'evidence': ['Write-up: https://example.com/short-prompts'],
     'freshness': {'checked': 'today', 'result': 'fresh', 'resurface_reason': 'seen again in a new post'}, 'prompt': 'Rewrite the morning digest prompt to be shorter.'},
    {'id': 'f-sandbox-03', 'type': 'risk', 'key': 'k3', 'age': 1800, 'state': 'new', 'confidence': 0.55, 'actionability': 'check',
     'title': 'An example dependency is end-of-life (synthetic)', 'why': 'It stops getting security fixes next month.',
     'evidence': ['Notice: https://example.com/eol-notice'], 'freshness': {'checked': 'yesterday', 'result': 'downgraded'},
     'prompt': 'List what depends on the old package and suggest a replacement.'},
    {'id': 'f-sandbox-04', 'type': 'tool', 'key': 'k4', 'age': 30000, 'state': 'saved', 'confidence': 0.64, 'actionability': 'later',
     'title': 'A diagram tool that reads plain text (synthetic)', 'why': 'Could draw the release flow from the checklist.',
     'evidence': ['Docs: https://example.com/diagram-tool'], 'freshness': {'checked': 'last week', 'result': 'fresh'},
     'prompt': 'Draw the release checklist as a diagram.'},
    {'id': 'f-sandbox-05', 'type': 'idea', 'key': 'k5', 'age': 60000, 'state': 'dismissed', 'confidence': 0.4, 'actionability': 'maybe',
     'title': 'Weekly photo digest (synthetic)', 'why': 'A low-priority idea from an older batch.',
     'evidence': ['No link: synthetic idea.'], 'freshness': {'checked': 'last week', 'result': 'fresh'}, 'prompt': 'Sketch a weekly photo digest.'},
]
RADAR_NOTES = [{'id': 'n-sandbox-01', 'kind': 'curator', 'title': 'The curator tidied a skill (synthetic)',
                'body': 'meeting-notes now asks for the date first. Tap to see the change.',
                'diff': '--- a/meeting-notes/SKILL.md\n+++ b/meeting-notes/SKILL.md\n@@ -3,2 +3,3 @@\n # Meeting notes\n+Ask for the meeting date first.\n Summarize the notes.\n'}]
MOMENTUM = {
    'version': 1, 'generated_at': '2026-11-04T08:00:00+00:00',
    'needs_you': [{'id': 'm-need-1', 'title': 'Pick a name for the example release (synthetic)', 'detail': 'Two drafts are waiting for your choice.', 'at': '2026-11-04T07:30:00+00:00', 'links': []},
                  {'id': 'm-need-2', 'title': 'Review the example checklist (synthetic)', 'detail': 'One item is still open.', 'at': '2026-11-04T06:10:00+00:00',
                   'links': [{'label': 'Checklist', 'url': 'https://example.com/checklist'}]}],
    'problems': [{'id': 'm-prob-1', 'title': 'An example nightly job failed once (synthetic)', 'detail': 'It passed on retry.', 'at': '2026-11-03T23:00:00+00:00', 'links': []}],
    'running': [{'id': 'm-run-1', 'title': 'Synthetic docs agent is drafting', 'detail': 'Started 20 minutes ago.', 'links': []}],
    'shipped': [{'id': 'm-ship-1', 'title': 'Example dark mode (synthetic)', 'effect': 'Fewer late-night complaints in the example data', 'detail': 'Merged yesterday.',
                 'at': '2026-11-03T15:00:00+00:00', 'links': [{'label': 'Example PR', 'url': 'https://example.com/pr/1'}]},
                {'id': 'm-ship-2', 'title': 'Example faster sync (synthetic)', 'effect': 'sync 2.1 s -> 0.8 s (illustrative)', 'detail': 'Merged two days ago.',
                 'at': '2026-11-02T12:00:00+00:00', 'links': []}],
    'decided': [{'id': 'm-dec-1', 'title': 'Keep weekly reviews on Mondays (synthetic)', 'detail': 'Decided last week.', 'at': '2026-10-28T09:00:00+00:00', 'links': []}],
    'counts': {'needs_you': 2, 'problems': 1, 'running': 1, 'shipped': 6, 'decided': 1},
}

# ---------------------------------------------------------------- Fleet (synthetic agents on a synthetic machine)
FLEET_AGENTS = [
    {'id': 'remote:demo-box:review-bot', 'kind': 'claude', 'name': 'Synthetic review agent', 'group': 'waiting',
     'detail': 'Waiting for your answer: ship the example fix? (synthetic)', 'where': 'ssh', 'host': 'demo-box', 'project': 'example-site',
     'model': 'synthetic-demo', 'can_reply': True, 'can_stop': False, 'unguarded': True, 'via': 'ssh',
     'lines': ['Synthetic agent on a synthetic host. Nothing runs anywhere.', '', 'Plan: fix the example typo on the home page.', 'Ship it? (yes/no)']},
    {'id': 'kitty:docs', 'kind': 'claude', 'name': 'Synthetic docs agent', 'group': 'working', 'detail': 'Drafting docs (synthetic)',
     'where': 'kitty', 'host': '', 'project': 'example-notes', 'model': 'synthetic-demo', 'can_reply': True, 'can_stop': False,
     'unguarded': False, 'via': 'kitty', 'terminal': True,
     'lines': ['Synthetic docs agent · example-notes', 'Reading the outline (synthetic)…', 'Choose the next section:']},
    {'id': 'tmux:build', 'kind': 'codex', 'name': 'Synthetic build watcher', 'group': 'working', 'detail': 'Watching an example build (synthetic)',
     'where': 'tmux', 'host': '', 'project': 'example-site', 'model': 'example-mini', 'can_reply': True, 'can_stop': True,
     'unguarded': False, 'via': 'tmux', 'lines': ['$ example build --watch   (synthetic, not executed)', 'compiled 12 example files', 'waiting for changes…']},
    {'id': 'tmux:old-task', 'kind': 'codex', 'name': 'Synthetic finished task', 'group': 'recent', 'detail': 'Ended an hour ago (synthetic)',
     'where': 'tmux', 'host': '', 'project': 'example-notes', 'model': 'example-mini', 'can_reply': False, 'can_stop': False,
     'unguarded': False, 'via': 'tmux', 'lines': ['Synthetic task finished. Nothing ran.']},
]
TERMINAL_OPTIONS = ['1. Getting started', '2. Settings', '3. Troubleshooting']

# ---------------------------------------------------------------- Projects (synthetic git repos)
PROJECTS = [
    {'id': 'example-notes', 'branch': 'main', 'defaultBranch': 'main', 'ahead': 0, 'behind': 0, 'age': 900,
     'files': [{'path': 'notes/checklist.md', 'staged': False, 'untracked': False, 'conflicted': False, 'added': 2, 'removed': 1,
                'diff': '--- a/notes/checklist.md\n+++ b/notes/checklist.md\n@@ -1,3 +1,4 @@\n # Checklist\n-- [ ] review\n+- [x] review\n+- [ ] test on a phone\n'},
               {'path': 'notes/ideas.md', 'staged': False, 'untracked': True, 'conflicted': False, 'added': 3, 'removed': 0,
                'diff': '--- /dev/null\n+++ b/notes/ideas.md\n@@ -0,0 +1,3 @@\n+# Ideas\n+- weekly photo digest\n+- shorter cron prompts\n'}]},
    {'id': 'example-site', 'branch': 'feature/dark-mode', 'defaultBranch': 'main', 'ahead': 2, 'behind': 0, 'age': 7200,
     'files': [{'path': 'src/theme.css', 'staged': True, 'untracked': False, 'conflicted': False, 'added': 4, 'removed': 2,
                'diff': '--- a/src/theme.css\n+++ b/src/theme.css\n@@ -1,4 +1,6 @@\n :root {\n-  --bg: #fff;\n-  --fg: #111;\n+  --bg: #111;\n+  --fg: #eee;\n+  --accent: #d4f75c;\n+  --muted: #888;\n }\n'}]},
]
# Mirrors server/mobile/terminal.py KEYS; a test keeps them equal.
TERMINAL_KEYS = frozenset(('escape enter tab up down left right home end page_up page_down backspace space ' +
                           ' '.join(str(i) for i in range(1, 10))).split())
