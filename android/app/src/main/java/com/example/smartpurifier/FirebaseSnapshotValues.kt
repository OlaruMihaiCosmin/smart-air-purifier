package com.example.smartpurifier

import com.google.firebase.database.DataSnapshot

// Punct unic de citire, verificat cu DataSnapshot real din SDK in testele locale.
fun valoareDinSnapshot(snapshot: DataSnapshot, tip: Class<out Any>): Any? =
    // Firebase respinge Map.class generic; valoarea bruta pastreaza tipurile JSON native.
    if (tip == Map::class.java) snapshot.value else snapshot.getValue(tip)
