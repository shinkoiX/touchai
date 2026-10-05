package app.touchai.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Keeps the live chat's lifetime independent of the activity presenting it. */
internal class ChatSessionOwner : ViewModel(), ViewModelStoreOwner {
    private var ownedStore: ViewModelStore? = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = ownedStore!!

    fun detach(): ViewModelStore = viewModelStore.also { ownedStore = null }

    fun adopt(store: ViewModelStore) {
        ownedStore?.clear()
        ownedStore = store
    }

    override fun onCleared() { ownedStore?.clear() }
}

internal class ChatSessionTransfer {
    private var pending: ViewModelStore? = null

    fun offer(store: ViewModelStore) {
        pending?.clear()
        pending = store
    }

    fun take(): ViewModelStore? = pending.also { pending = null }

    fun clear() { pending?.clear(); pending = null }
}
