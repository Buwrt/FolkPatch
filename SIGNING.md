# Signing

Every build of this fork is signed with the keystore committed at
`folkpatch.jks` (alias `folkpatch`, password `folkpatch`).

KernelPatch authorises the manager by its signing certificate and bakes that
certificate into the patched boot image. If two builds are signed differently,
a boot image patched by one build will not recognise the manager installed from
another: the home screen then reports that the kernel patch is not installed
and that root is unavailable, even though the boot image really is patched.

Keeping one shared keystore means:

- updates install in place, without an uninstall that would wipe the saved
  SuperKey;
- a boot image stays valid across manager updates.

To use your own key instead, set the repository secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`; the workflow prefers them
over the committed keystore.

## After changing the signing key

Re-patch and re-flash the boot image once with the new manager, so the new
certificate is written into the image.
