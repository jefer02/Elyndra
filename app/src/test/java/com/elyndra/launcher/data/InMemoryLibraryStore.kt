package com.elyndra.launcher.data

/** Tienda de biblioteca en memoria para las pruebas: guarda la última instantánea. */
class InMemoryLibraryStore(initial: Library = Library()) : LibraryStore {

    var saved: Library = initial
        private set

    var saves: Int = 0
        private set

    override suspend fun load(): Library = saved

    override suspend fun save(previous: Library, next: Library) {
        saved = next
        saves++
    }
}
