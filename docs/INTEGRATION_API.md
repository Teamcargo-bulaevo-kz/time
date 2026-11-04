# TIME Integration API

This document defines the intended public boundary between TIME and external applications, especially Frend. It is a contract proposal until implemented and versioned in code.

## Goals

- Frend can manage alarms without depending on TIME screens or internal database classes.
- TIME remains independently usable if Frend is absent, stopped, or uninstalled.
- Every action is explicit, permission-checked, and visible to the user where appropriate.
- External apps cannot silently defeat the emergency stop or change safety settings.

## API boundary

The first implementation should use explicit Android Intents with a versioned action namespace:

- kz.teamcargo.time.action.GET_STATUS
- kz.teamcargo.time.action.LIST_ALARMS
- kz.teamcargo.time.action.CREATE_ALARM
- kz.teamcargo.time.action.UPDATE_ALARM
- kz.teamcargo.time.action.SET_ALARM_ENABLED
- kz.teamcargo.time.action.OPEN_ALARMS
- kz.teamcargo.time.action.OPEN_RINGING

The exact transport (activity result, broadcast receiver, or bound service) must be selected based on Android background-execution rules before implementation. Do not expose an unrestricted exported receiver.

## Data contract (draft)

Alarm:
- id: stable UUID string
- hour, minute: local wall-clock time
- repeatDays: ISO weekday numbers 1–7
- enabled: boolean
- label: optional string
- soundProfile: rain | thunderstorm | music | custom
- gradualVolume: boolean
- rampDurationMinutes: integer
- flashEnabled: boolean
- vibrationEnabled: boolean
- wakeCheckEnabled: boolean
- repeatCheckMinutes: integer (1–30, default 5)

Status:
- apiVersion: integer
- nextAlarmAt: ISO-8601 timestamp with offset, or null
- ringing: boolean
- activeAlarmId: UUID string, or null
- lastWakeCheck: passed | pending | interrupted | none

## Security

- Keep components non-exported by default.
- If external access is required, use explicit caller allowlisting/signature permission where feasible and verify the caller identity.
- Validate every input; never trust an external package name or payload field.
- Require user confirmation before creating or changing alarms through an external assistant, unless the user has explicitly enabled a trusted integration mode.
- External commands cannot disable emergency stop, silence a currently ringing alarm without a visible user action, or change the configured emergency behavior.
- Do not expose microphone or sleep-monitoring data through this API in the first version.

## Compatibility

- Prefix all actions and extras with a versioned namespace.
- Keep API changes backward-compatible where possible.
- Document minimum TIME version for each operation.
- Provide a small example caller in the Frend integration documentation after the first working API implementation.
