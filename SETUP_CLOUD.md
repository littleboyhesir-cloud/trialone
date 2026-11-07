# Cloud login and backup setup (free, about 10 minutes)

This lets you sign in with email and password so your data survives a lost phone.
It uses your own free Firebase project. Only your account can read your data.

1. Go to https://console.firebase.google.com and sign in with a Google account.
2. Click **Add project**, give it a name (for example `stayfree`), turn Google Analytics off, click **Create**.
3. Left menu: **Build > Authentication > Get started > Email/Password**. Turn **Enable** on and Save.
4. Left menu: **Build > Firestore Database > Create database**. Choose any location, pick **Production mode**, then Create.
5. In Firestore, open the **Rules** tab, delete everything, paste this, and click **Publish**:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

6. Click the gear icon > **Project settings > General**. Copy:
   - **Project ID**
   - **Web API Key** (if you don't see one, click "Add app", choose the web icon `</>`, register it, then it appears)
7. Open the file `firebase.properties` in this project and paste them:

```
FIREBASE_API_KEY=AIza...your key...
FIREBASE_PROJECT_ID=your-project-id
```

8. Rebuild the APK (GitHub Actions > Build APK > Run workflow). Open the app > **Me** tab > Create account.

On a new phone: install the app, go to **Me**, Sign in. If the phone is empty your data is restored automatically.

Tips: keep your GitHub repo **private**. Use a strong password you don't use elsewhere.
