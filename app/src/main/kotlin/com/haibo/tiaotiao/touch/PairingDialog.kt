package com.haibo.tiaotiao.touch

/** Only the two public fields in a Settings pairing dialog may become pairing input. */
internal object PairingDialog {
    const val SETTINGS="com.android.settings"
    const val CODE="$SETTINGS:id/pairing_code"
    const val ADDRESS="$SETTINGS:id/ip_addr"
    class Field(val id: String,val value: String,val packageName: String=SETTINGS,
        val password: Boolean=false,val editable: Boolean=false) {
        override fun toString()="Field(id=$id, value=REDACTED)"
    }
    fun parse(owner: String,fields: List<Field>): PairingInput? {
        if(owner!=SETTINGS) return null
        val safe=fields.filter {it.packageName==SETTINGS && !it.password && !it.editable}
        val codes=safe.filter {it.id==CODE}.map {it.value.filterNot(Char::isWhitespace)}.distinct()
        val addresses=safe.filter {it.id==ADDRESS}.map {it.value.trim()}.distinct()
        if(codes.size!=1 || addresses.size!=1) return null
        val address=Regex("([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})").matchEntire(addresses.single()) ?: return null
        if(address.groupValues[1].split('.').any {(it.toIntOrNull() ?: 256)>255}) return null
        return PairingInput.parse(codes.single(),address.groupValues[2].toIntOrNull() ?: 0)
    }
}
