# Deployment transport reconciliation

The first guarded code upload returned an unconfirmed cloud CLI transport/response failure (`operator83-final-deploy.txt`). No release database action had occurred. Root ran the operator's read-only `--verify`; it reached a stable Active configuration but rejected the deployed member count (`operator83-final-deploy-reconcile.txt`), confirming the desired43-member content had not been independently verified.

Root then ran the original guarded `--deploy` again, with512MB bounded Node heap and a metadata-only child transport logger. Its unchanged pre-write guards reread the exact previous production archive/configuration before upload. The cloud API acknowledged the second upload. Independent post-write archive comparison verified all43 member contents and Unix modes and unchanged configuration. The logger never forwards payloads, credentials or child stdout/stderr; the underlying cause of the first failure is not established and is not claimed to be heap exhaustion.

Final deployment identities:

- APK `f320eef5e6068e94fff54f37509374d09f723492f6fb3077052d727e55d79ebd`
- Request ZIP `7a44057f762b195a6d35d00e3e6a8b646025c8f6b145c581f1bb02e7983b86bd`
- Provider-repacked ZIP `968788d46d3b3952bf2a203a25e795a57222d8fa10302e5f086b7f6e3e62773e`
- Complete member-content/mode identity `2a53a948b8b6a9c09f3f319d2ae368f40effc2b746df269c0d9a5fde82c7c2b7`

The different container encodings are accepted only because the full member/mode comparison passed. Before publication, `--inspect` reported no existing version83 row and preserved the published baseline. Deployment alone did not claim a published release.
