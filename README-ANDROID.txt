VBDCP Android App build (V56 web app bundled locally)

This Android project includes the VBDCP V56 web app in app/src/main/assets and launcher icon resources. It does not require a Netlify deployment to build or launch.

Build APK with GitHub Actions (no Netlify production deploy):
1. Create/use a GitHub branch named android-build. Keep your production main branch unchanged.
2. Upload/extract the CONTENTS of this project at the root of that branch (so .github/workflows/build-apk.yml is at repository root).
3. Open GitHub repository > Actions > Build VBDCP Android APK > Run workflow.
4. Download the VBDCP-debug-apk artifact from the completed run; install app-debug.apk on your Android phone.

The workflow is limited to android-build pushes and manual workflow dispatch. It does not deploy to Netlify.

Package: com.vbdcp.attendance | Version: 1.3 | Web app: V56
