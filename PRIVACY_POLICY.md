# Orin Privacy Policy

**Effective date:** 27 November 2026
**Applies to:** Orin for Android (`com.orin.app`)

> **Before publishing:** replace `PRIVACY_CONTACT@example.com` below with a real, monitored address and host this page at a stable public URL. Add that URL to the Play Store listing (App privacy policy) and to the Data Safety form.

## Summary

Orin is a personal AI assistant that runs on your device. It has no backend of its own: there is no Orin account, no Orin server, no analytics, no advertising SDK and no crash-reporting upload. Your chat history and credentials stay on your device.

The only network traffic Orin makes is to the AI provider and tools you explicitly configure.

## What Orin stores on your device

| Data | Where it is stored | Sent off-device? |
| --- | --- | --- |
| Chat history and conversation titles | Local Room database, app-private storage | No |
| Tasks created through the assistant | Local Room database | No |
| Audit log of side-effecting tool actions | Local Room database | No |
| AI provider API keys | Android Keystore-backed `EncryptedSharedPreferences` | Only to the provider the key belongs to |
| Meta Ads account ID and access token | Android Keystore-backed `EncryptedSharedPreferences` | Only to Meta's Graph API |
| Files created by the workspace tool | App-private `filesDir/workspace` | No |
| Offline speech model (Vosk) | App-private storage, downloaded at build time | No |
| Saved memories | Local Room database | No |
| Recent notification summaries | In memory only (cleared when the process ends) | The masked summary is sent to your chosen AI provider only when you ask "what did I miss?" |

Backups and device-to-device transfer are explicitly disabled. Unticking "Allow backup" is deliberate: an encrypted keystore restored onto another device is unusable, and chat history should not leave the phone.

## What leaves the device

1. **Your prompts and conversation context** are sent to the AI provider you selected (Google Gemini, Anthropic Claude, OpenAI or DeepSeek) so it can generate a reply. The request includes your API key, sent in an HTTPS header (not a URL).
2. **A URL you ask Orin to fetch** is requested over HTTPS from that URL's host. Orin blocks local, loopback, private and link-local addresses, and re-validates redirects.
3. **Meta Ads requests** are sent to `graph.facebook.com` only when the Meta Ads tool is called, with your access token in an HTTPS header.
4. **SMS, calls and calendar actions** are performed on-device via Android APIs. Orin does not relay their contents anywhere.

You choose the provider by entering your own API key. Each provider's own privacy policy and retention rules apply to data once it reaches them.

## Permissions and why they are requested

| Permission | Why | When used |
| --- | --- | --- |
| Internet | Talk to your chosen AI provider and fetch URLs | Every chat turn |
| Calendar (read/write) | Show upcoming events and create an event you ask for | Only when the calendar tool runs |
| Contacts (read) | Find a contact for a message you ask to send | Only when the contacts tool runs |
| SMS (read/send) | Show recent messages and send a text you approve | Only when the SMS tool runs |
| Microphone | Speak to Orin using the offline speech model | Only while you hold the voice button or the orb is listening |
| Camera (torch only) | Switch the flashlight on or off when you ask | Only when that tool runs |
| Exact alarms | Deliver reminders at the time you choose | Only when you set a reminder |
| Display over other apps | Show the floating voice orb | Only while the orb is enabled |
| Accessibility | Let Orin read and operate an app on your instruction | Only while you have it enabled; every risky action needs your tap |

All of these are optional and requested at runtime. Device features requiring them remain available if you deny them. The shell and file tools run inside a private workspace and never receive Android shell permissions.

Voice recognition prefers the device's on-device speech model in Bangla (`bn-BD`), Hindi and English. Orin never records or uploads audio itself; the system speech service may use the network if your device has no offline language pack installed. Screen control never sends screenshots anywhere. Orin refuses to operate banking, UPI, password-manager, Play Store and Settings apps, never types a PIN, password, OTP or card number, and requires a finger tap on an approval card before it changes anything in another app.

## Human approval

Orin asks for explicit confirmation before any action that changes something outside the app: sending an SMS, writing a file, running a shell command, creating a calendar event, launching an app or changing the volume. Every such action is written to a local audit log. If you deny a request, the action is not performed.

Tool output (web pages, SMS bodies, contact names) is treated as untrusted data and is never allowed to trigger an action by itself.

## Retention and deletion

Data remains on your device until you delete it. Use **Settings → Clear all local data** to delete chats, tasks and the audit log. To remove credentials, clear the corresponding field in **Settings** and save it empty, or uninstall the app. Uninstalling removes all app data.

## Children

Orin is not intended for children under 13. We do not knowingly collect data from children.

## Security

- HTTPS is enforced; cleartext traffic is disabled.
- API keys and tokens never appear in URLs, logs or the APK.
- Release builds are signed with a 4096-bit RSA key, R8-minified, and are not debuggable.
- The app has no exported components other than its launcher activity.

## Changes

Changes to this policy will be reflected in the app and in the repository. Material changes will be called out in the release notes.

## Contact

Questions or requests: **PRIVACY_CONTACT@example.com**
