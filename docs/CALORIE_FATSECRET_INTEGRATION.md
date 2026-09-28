# Calories: FatSecret integration contract

Status: integration under development. The user confirmed FFF is in a testing phase before general availability and explicitly chose not to retain FatSecret catalog content beyond 24 hours. The OAuth 2 credentials are configured only in the Asus deployment environment, and the user allowlisted its observed public IP. Never copy those credentials into this repository or an APK.

## Existing product boundary

The FFF Android Calories diary is private, local-first and persists reusable foods plus immutable nutrition snapshots in `fff-calorie.db`. It is not generally accessible to the public. The app also works offline.

## FatSecret requirements and blockers

- FatSecret issues an OAuth 2 Client ID and Client Secret. OAuth 2 `client_credentials` tokens require a server-side proxy; routing subsequent API calls through the FFF server is our additional security design. Neither credential nor token belongs in the Android APK, Git, logs or URLs. The OAuth 1 Consumer Secret visible in the user's screenshot is **not** the OAuth 2 Client Secret and should be rotated because it was disclosed. Source: https://platform.fatsecret.com/docs/guides/authentication/oauth2
- The Food Search API provides paginated food IDs and names; a food details call supplies servings and nutritional values. Barcode lookup requires the `barcode` scope and a 13-digit GTIN, with UPC/EAN normalization. Sources: https://platform.fatsecret.com/docs/v1/foods.search and https://platform.fatsecret.com/docs/v2/food.find_id_for_barcode
- The standard terms require applications to be generally accessible to users, except during development/testing or with a separate agreement/written permission. FFF is currently owner-only during development and intended for wider use later. An account and normal API credentials alone do not prove either exception. Source: https://platform.fatsecret.com/terms section 1.2.
- FatSecret content other than a narrow list of IDs must be removed or re-requested within 24 hours. Food names, nutrition values and historical snapshots are **not** on that list. FFF's permanent offline food catalog and diary snapshots therefore cannot automatically persist FatSecret content under the standard terms. Sources: https://platform.fatsecret.com/docs/guides/storable-data and https://platform.fatsecret.com/terms section 1.5.
- Attribution is required wherever FatSecret content is shown. Source: https://platform.fatsecret.com/terms section 1.3.

## Safe implementation boundary

The implementation separates FatSecret food details from local diary records. Room stores only the FatSecret `food_id` and `serving_id`, plus the user's date, meal and portion. Food names, brand, serving labels and per-product nutrition are transient and re-requested on demand; they are not written to Room or preferences and must be discarded after 24 hours. The user additionally asked to retain a daily aggregate of consumed calories and macros permanently as a diary summary, without per-product details. Whether this derived user aggregate falls outside FatSecret “Content” is an interpretation, not an express exception in the public terms; confirm with FatSecret before broad release. The public-release plan may satisfy the general-access requirement; current owner-only development is a distinct testing phase.

To repair an accidental portion without a forbidden permanent nutrient snapshot,
the Android diary offers a manual editor for that day's external aggregate. The
owner enters the corrected full-day calories and macros and may remove the
selected ID-only row in the same Room transaction. Current provider data is not
silently used to subtract old values, because the source may have changed.

The UI must visibly link the exact attribution phrase `Powered by fatsecret Platform API` to https://platform.fatsecret.com on every screen showing FatSecret results and at least one pre-login screen. See https://platform.fatsecret.com/attribution.

If permission is obtained, expose owner-authenticated search and barcode routes through the existing FFF server; scope and rate-limit them, use short timeouts, verify FatSecret errors, retain no server-side response cache beyond the license allowance, and provide clear source attribution. Define the permitted local storage and expiration behavior from the **actual agreement** before writing data to Room. Preserve existing local diary behavior and test the transition on an upgrade.

## Credentials and rollout checklist

1. The owner provided the OAuth 2 Client ID/Secret. They are installed in the production `/home/fff/fff/.env` with mode 0600. An initial placement in `/home/rockxi/fff/.env` was corrected and its FatSecret keys removed. Because the secret was sent in chat, rotate it after verifying the integration and update the production value securely. Never put it in Git, the APK, logs or URLs.
2. Asus egress was verified as `95.84.154.46`, matching the user's FatSecret whitelist entry. A live OAuth request succeeded and returned only the `basic` scope. Live `foods/search/v5` rejected with missing `premier`, while `foods/search/v1` and `food/v1` succeeded. Live barcode v2 rejected with missing `barcode`; scanner lookup must visibly report that missing access until FatSecret grants it, while manual/local logging remains available.
3. Verify the development/testing phase and confirm the treatment of permanent user-created daily aggregate nutrition summaries with FatSecret before broad release; never persist individual FatSecret product details longer than permitted.
4. Deploy the owner-authenticated routes through the existing GitHub Actions pipeline, then exercise search, barcode scope failure, 401/429/timeout, no-result and attribution states. Request `barcode` access from FatSecret before advertising barcode lookup as available.
