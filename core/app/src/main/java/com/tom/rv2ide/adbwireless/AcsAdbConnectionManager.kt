package com.tom.rv2ide.adbwireless

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * Implementasi ADB (protokol ADB murni di Kotlin/Java) sehingga TIDAK butuh binary `adb` /
 * `pkg install android-tools`. Key RSA + sertifikat disimpan di filesDir agar pairing tetap
 * berlaku setelah aplikasi ditutup.
 */
class AcsAdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

  private val dir = File(context.filesDir, "adb").apply { mkdirs() }
  private val keyFile = File(dir, "adb_private_key.pk8")
  private val certFile = File(dir, "adb_cert.der")

  private lateinit var keyPrivate: PrivateKey
  private lateinit var keyCert: Certificate

  init {
    setApi(Build.VERSION.SDK_INT)
    if (!loadKeys()) {
      generateKeys()
    }
  }

  override fun getPrivateKey(): PrivateKey = keyPrivate

  override fun getCertificate(): Certificate = keyCert

  override fun getDeviceName(): String = "AndroidCodeStudio"

  private fun loadKeys(): Boolean {
    return try {
      if (!keyFile.exists() || !certFile.exists()) return false
      keyPrivate =
          KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
      keyCert =
          CertificateFactory.getInstance("X.509")
              .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
      true
    } catch (e: Throwable) {
      false
    }
  }

  private fun generateKeys() {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048, SecureRandom())
    val pair = generator.generateKeyPair()
    val publicKey = pair.public
    val privateKey = pair.private

    val algorithmName = "SHA512withRSA"
    val notBefore = Date()
    val notAfter = Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
    val x500Name = X500Name("CN=Android Code Studio")

    val extensions = CertificateExtensions()
    extensions.set(
        "SubjectKeyIdentifier",
        SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
    )
    extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))

    val info = X509CertInfo()
    info.set("version", CertificateVersion(2))
    info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
    info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
    info.set("subject", CertificateSubjectName(x500Name))
    info.set("key", CertificateX509Key(publicKey))
    info.set("validity", CertificateValidity(notBefore, notAfter))
    info.set("issuer", CertificateIssuerName(x500Name))
    info.set("extensions", extensions)

    val cert = X509CertImpl(info)
    cert.sign(privateKey, algorithmName)

    keyPrivate = privateKey
    keyCert = cert

    try {
      keyFile.writeBytes(privateKey.encoded)
      certFile.writeBytes(cert.encoded)
    } catch (e: Throwable) {
      // Gagal menyimpan => pairing harus diulang di sesi berikutnya, tapi sesi ini tetap jalan.
    }
  }

  companion object {
    fun create(context: Context): AcsAdbConnectionManager =
        AcsAdbConnectionManager(context.applicationContext)
  }
}
