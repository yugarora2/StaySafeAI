# Firestore Security Rules

Paste these into Firebase Console → Firestore → Rules

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // User's own scan history — private
    match /users/{userId}/scans/{scanId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }

    // Community scans — anyone authenticated can read
    // Write is allowed but userId is never stored (anonymous)
    match /community_scans/{scanId} {
      allow read: if request.auth != null;
      allow create: if request.auth != null
        && !('userId' in request.resource.data);  // enforce anonymity
    }

    // Location summaries — read by all, write only via cloud function
    match /location_summaries/{locationKey} {
      allow read: if request.auth != null;
      allow write: if request.auth != null;
    }
  }
}
```

# Required Firestore Indexes

Create these in Firebase Console → Firestore → Indexes:

1. Collection: `community_scans`
   - Fields: `locationKey` ASC, `timestamp` DESC

2. Collection: `location_summaries`
   - Fields: `hotelName` ASC

3. Collection: `users/{uid}/scans`
   - Fields: `timestamp` DESC
