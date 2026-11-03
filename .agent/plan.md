# Project Plan

An Android application for restaurant tablets that automatically launches on boot, displays a specific URL (online menu) in full screen, and acts as a kiosk. The tablet must be locked down (Kiosk Mode/Lock Task Mode) so customers cannot access other apps or settings. Exiting the kiosk mode or accessing tablet settings must require an admin password. Please respond in Turkish.

## Project Brief

# Proje Özeti: Restoran Menü Kiosk Uygulaması

Bu belge, restoran tabletleri için özel olarak tasarlanmış, cihaz başlatıldığında otomatik olarak açılan ve çevrimiçi menüyü tam ekran gösteren Kiosk (Görev Kilidi) uygulamasının Minimum Uygulanabilir Ürün (MVP) detaylarını içermektedir.

## Özellikler

1. **Tam Ekran Web Menüsü:** Belirlenen çevrimiçi restoran menüsü URL'sini, durum çubuğu ve gezinme çubukları gizlenmiş bir şekilde, tam ekran (immersive mode) `WebView` içerisinde gösterir.
2. **Kiosk Modu (Görev Kilitleme):** Android Lock Task Mode kullanılarak müşterilerin uygulamadan çıkması, cihaz ayarlarına girmesi veya başka uygulamalara geçiş yapması engellenir.
3. **Otomatik Başlatma (Auto-boot):** Cihaz açıldığında veya yeniden başlatıldığında uygulamanın otomatik olarak çalışmasını sağlar, manuel müdahaleye gerek bırakmaz.
4. **Şifreli Yönetici Paneli:** Uygulamanın Kiosk modundan çıkarılması veya ayarlarının (örn: menü URL'sinin) değiştirilmesi için yalnızca şifre ile erişilebilen gizli bir yönetici kontrol alanı sunar.

## Üst Düzey Teknoloji Yığını

- **Programlama Dili:** Kotlin
- **Kullanıcı Arayüzü (UI):** Jetpack Compose (Modern ve bildirimsel arayüz geliştirme)
- **Web Entegrasyonu:** Android `WebView` (Jetpack Compose `AndroidView` sarmalayıcısı ile)
- **Navigasyon ve Uyarlanabilir Strateji:** Tablet ekranlarına kusursuz uyum ve ekranlar arası geçişler için **Jetpack Navigation 3** (durum güdümlü / state-driven) ve **Compose Material Adaptive** kütüphanesi.
- **Kiosk Modu ve Cihaz Yönetimi:** Android `DevicePolicyManager` ve `LockTask` API'leri (Cihaz Sahibi yetkileri - Device Owner). Cihaz başlatma tespiti için `BroadcastReceiver` (ACTION_BOOT_COMPLETED).
- **Asenkron İşlemler:** Kotlin Coroutines (Arka plan işlemleri ve UI olaylarının yönetimi için).

## Implementation Steps
**Total Duration:** 6m 44s

### Task_1_BootAndPermissions: Temel proje yapılandırmasını yap, gerekli izinleri (İnternet, Boot) Manifest'e ekle ve cihaz açıldığında uygulamayı başlatacak BroadcastReceiver'ı (Auto-boot) oluştur.
- **Status:** COMPLETED
- **Updates:** Gerekli Manifest izinleri eklendi ve BootReceiver oluşturuldu. Başarılı bir şekilde build edildi.
- **Acceptance Criteria:**
  - Manifest izinleri eklendi
  - BootReceiver uygulandı ve MainActivity'yi başlatıyor
- **Duration:** 2m 43s

### Task_2_KioskModeAdmin: Kiosk Modu (Lock Task Mode) için DeviceAdminReceiver ve DevicePolicyManager ayarlarını uygula. MainActivity'yi Kiosk modunda başlatacak mantığı kur.
- **Status:** COMPLETED
- **Updates:** KioskDeviceAdminReceiver oluşturuldu, manifest ayarları yapıldı ve startLockTask mantığı eklendi. Cihaz yöneticisi olarak çalışabilecek şekilde yapılandırıldı.
- **Acceptance Criteria:**
  - DeviceAdminReceiver oluşturuldu
  - Lock Task Mode başlatılabiliyor
  - Uygulama arka plana atılamıyor veya kapatılamıyor
- **Duration:** 1m 3s

### Task_3_ComposeWebViewAndAdmin: Jetpack Compose kullanarak tam ekran WebView menü arayüzünü oluştur. Kiosk modundan çıkmak için şifreli (şifre korumalı) gizli bir yönetici paneli (Admin Panel) ekle.
- **Status:** COMPLETED
- **Updates:** Compose ile tam ekran WebView eklendi. Sistem çubukları gizlendi (Immersive mode). Sağ üst köşeye gizli bir admin butonu eklenip, şifre ile stopLockTask() çalıştıran dialog entegre edildi.
- **Acceptance Criteria:**
  - URL WebView'da tam ekran yükleniyor
  - Sistem çubukları gizlendi (Immersive mode)
  - Gizli admin butonuna tıklandığında şifre soruluyor ve kiosk modundan çıkılabiliyor
- **Duration:** 50s

### Task_4_RunAndVerify: Uygulamayı derle ve çalıştır. Critic agent'ı kullanarak uygulamanın kararlılığını test et, kullanıcı gereksinimlerine (Boot, Kiosk, WebView, Admin şifresi) uygunluğunu doğrula.
- **Status:** COMPLETED
- **Updates:** Uygulama başarıyla derlendi ve tablet form faktöründe test edildi. Uygulama çökmedi, WebView tam ekran (immersive mode) olarak çalıştı ve sağ üst köşedeki gizli yönetici diyaloğu başarılı bir şekilde görüntülenebildi.
- **Acceptance Criteria:**
  - Proje başarıyla derleniyor (build pass)
  - Uygulama çökmüyor (app does not crash)
  - Tüm mevcut testler geçiyor
  - Kiosk, WebView, Admin ve Boot özellikleri çalışıyor
- **Duration:** 2m 8s

