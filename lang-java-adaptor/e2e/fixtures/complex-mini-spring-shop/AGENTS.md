# Evaluation workspace

- Anatomist is available on PATH. Start with `anatomist --help`.
- Obey the prompt's edit boundary. Analysis-only tasks must not change source or configuration.
- For implementation tasks, run the relevant Maven tests and re-index changed Java files before reporting completion.
- Do not commit, push, switch branches, or modify the acceptance-test module.
- Save temporary evidence under `../evidence/` so the owned workspace cleanup removes it.
