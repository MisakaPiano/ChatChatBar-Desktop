# Raw log delivery correction

The initial source/evidence commit `5061b561826c20e8a266857252ddb9253ed7a53c`
included the71-file raw index but omitted21 `.log` files due to the repository's
default ignore rule. The pre-commit staged-check subprocess failed; the calling
PowerShell command did not stop the subsequent commit. This was an evidence
delivery error, not a new test failure. Local logs, fixtures and their indexed
bytes were preserved unchanged throughout.

The follow-up explicitly stages only those21 already reviewed synthetic evidence
logs. All71 indexed raw files have now been read from Git's index and verified for
byte length and SHA-256; the two final test source Git blobs also match their
pre-test identities. No source/production/test rerun or history rewrite occurs.
The original commit remains in history so the correction is auditable.

For shell orchestration, a successful later command is not proof that an earlier
validation subprocess succeeded. This follow-up performs the index verification
as a separate tool call and observes its successful result before committing.
