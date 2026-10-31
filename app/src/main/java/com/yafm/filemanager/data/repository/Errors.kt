package com.yafm.filemanager.data.repository

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.yafm.filemanager.R
import com.yafm.filemanager.str

/** A message for the user. */
fun describeError(e: Throwable): String {
    val name = e.javaClass.simpleName
    return when {
        name == "JSchChangedHostKeyException" -> str(R.string.the_server_s_key_has_changed_the_server)
        name == "JSchUnknownHostKeyException" -> str(R.string.server_key_not_trusted)
        e is java.net.UnknownHostException -> str(R.string.server_not_found, e.message)
        e is java.net.ConnectException || e is java.net.SocketTimeoutException -> str(R.string.the_server_does_not_answer)
        name == "JSchException" && e.message?.contains("Auth fail") == true -> str(R.string.wrong_user_name_or_password)
        e is SftpException && e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> str(R.string.file_not_found)
        e is SftpException && e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> str(R.string.permission_denied)
        else -> e.message?.takeIf { it.isNotBlank() } ?: name
    }
}
