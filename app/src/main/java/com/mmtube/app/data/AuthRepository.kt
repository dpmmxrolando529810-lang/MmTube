package com.mmtube.app.data

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val context: Context,
    private val auth: FirebaseAuth?
) {

    private val _state = MutableStateFlow<AuthState>(
        if (auth == null) AuthState.SignedOut else AuthState.Loading
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _state.value = if (user == null) {
            AuthState.SignedOut
        } else if (user.isAnonymous) {
            AuthState.Anonymous(user.uid)
        } else {
            AuthState.SignedIn(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
                photoUrl = user.photoUrl?.toString()
            )
        }
    }

    init {
        auth?.addAuthStateListener(listener)
        // Guests get a silent anonymous session so server-side quota is enforceable.
        auth?.let { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                runCatching {
                    firebaseAuth.signInAnonymously().addOnSuccessListener { result ->
                        _state.value = AuthState.Anonymous(result.user?.uid ?: "")
                    }
                }
            } else if (firebaseAuth.currentUser?.isAnonymous == true) {
                _state.value = AuthState.Anonymous(firebaseAuth.currentUser?.uid.orEmpty())
            }
        }
    }

    suspend fun signInAnonymously(): Boolean {
        val firebaseAuth = auth ?: return false
        return runCatching { firebaseAuth.signInAnonymously().await() }.isSuccess
    }

    val currentUid: String?
        get() = auth?.currentUser?.uid

    suspend fun signIn(activity: Activity): Result<AuthState.SignedIn> {
        val firebaseAuth = auth
            ?: return Result.failure(MmtubeException(ErrorKind.FIREBASE, "Cloud accounts are not configured"))
        if (!FirebaseConfig.canSignIn) {
            return Result.failure(
                MmtubeException(ErrorKind.SIGN_IN_UNAVAILABLE, "Google Web Client ID is missing")
            )
        }
        return runCatching {
            val credentialManager = CredentialManager.create(activity)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(FirebaseConfig.webClientId)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(activity, request)
            val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
            val user = firebaseAuth.signInWithCredential(firebaseCredential).await().user
                ?: throw MmtubeException(ErrorKind.FIREBASE, "No Firebase user returned")
            AuthState.SignedIn(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
                photoUrl = user.photoUrl?.toString()
            ).also { _state.value = it }
        }.recoverCatching { error ->
            Log.w("AuthRepository", "Google sign-in failed", error)
            when (error) {
                is GetCredentialCancellationException -> throw MmtubeException(
                    ErrorKind.SIGN_IN_CANCELLED, error.message, error
                )
                is NoCredentialException -> throw MmtubeException(
                    ErrorKind.SIGN_IN_UNAVAILABLE, error.message, error
                )
                is GoogleIdTokenParsingException -> throw MmtubeException(
                    ErrorKind.SIGN_IN_UNAVAILABLE, error.message, error
                )
                is GetCredentialException -> throw MmtubeException(
                    ErrorKind.SIGN_IN_UNAVAILABLE, error.message, error
                )
                is MmtubeException -> throw error
                else -> throw MmtubeException(ErrorKind.FIREBASE, error.message, error)
            }
        }
    }

    /**
     * Email + password sign-in / registration. Used as the primary sign-in
     * method on networks where Google sign-in (Credential Manager) is not
     * reachable.
     */
    suspend fun signInWithEmail(
        email: String,
        password: String,
        createAccount: Boolean
    ): Result<AuthState.SignedIn> {
        val firebaseAuth = auth
            ?: return Result.failure(MmtubeException(ErrorKind.FIREBASE, "Cloud accounts are not configured"))
        return runCatching {
            val task = if (createAccount) {
                firebaseAuth.createUserWithEmailAndPassword(email.trim(), password)
            } else {
                firebaseAuth.signInWithEmailAndPassword(email.trim(), password)
            }
            val user = task.await().user
                ?: throw MmtubeException(ErrorKind.FIREBASE, "No Firebase user returned")
            AuthState.SignedIn(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
                photoUrl = user.photoUrl?.toString()
            ).also { _state.value = it }
        }.recoverCatching { error ->
            Log.w("AuthRepository", "Email sign-in failed", error)
            val message = when (error) {
                is FirebaseAuthWeakPasswordException -> "Password အနည်းဆုံး ၆ လုံး ဖြစ်ရမယ်"
                is FirebaseAuthUserCollisionException ->
                    "ဒီ email နဲ့ account ဆောက်ပြီးသား ဖြစ်တယ် — Sign in လုပ်ပါ"
                is FirebaseAuthInvalidUserException ->
                    "ဒီ email နဲ့ account မရှိသေးဘူး — Create account လုပ်ပါ"
                is FirebaseAuthInvalidCredentialsException ->
                    "Email သို့မဟုတ် password မှားနေတယ်"
                else -> "Sign in မအောင်မြင်ဘူး (${error.message})"
            }
            throw MmtubeException(ErrorKind.FIREBASE, message, error)
        }
    }

    fun signOut() {
        auth?.signOut()
        _state.value = AuthState.SignedOut
    }

    suspend fun deleteAuthAccount(): Result<Unit> {
        val firebaseAuth = auth ?: return Result.failure(MmtubeException(ErrorKind.FIREBASE, "Cloud not configured"))
        return runCatching {
            firebaseAuth.currentUser?.delete()?.await()
                ?: throw MmtubeException(ErrorKind.FIREBASE, "Not signed in")
            _state.value = AuthState.SignedOut
        }.recoverCatching { error ->
            if (error is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
                throw MmtubeException(ErrorKind.FIREBASE, "Security reasons: ပြန်လည်ဝင်ရောက်ပြီး ခေတ္တခဏ စောင့်ပါ — နောက်တစ်ကြိမ် ကြိုးစားပါ", error)
            }
            throw MmtubeException(ErrorKind.FIREBASE, error.message, error)
        }.map { Unit }
    }
}
