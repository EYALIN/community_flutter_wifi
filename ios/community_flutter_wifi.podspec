#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint community_flutter_wifi.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'community_flutter_wifi'
  s.version          = '1.0.0'
  s.summary          = 'Wi-Fi manager for Flutter: connection, IP and router details and a LAN device sweep.'
  s.description      = <<-DESC
Wi-Fi manager for Flutter: SSID/BSSID, network addresses, gateway and DNS, connected devices on the local network
(TCP + Bonjour) and network configuration through NEHotspotConfiguration.
                       DESC
  s.homepage         = 'https://github.com/EYALIN/community_flutter_wifi'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Community Flutter Plugins' => 'community-flutter-plugins@users.noreply.github.com' }
  s.source           = { :path => '.' }
  s.source_files = 'community_flutter_wifi/Sources/community_flutter_wifi/**/*', 'community_flutter_wifi/Sources/community_flutter_wifi_c/**/*.{h,c}'
  # cfw_net.c: DNS servers through libresolv (res_9_getservers).
  s.libraries = 'resolv'
  s.dependency 'Flutter'
  s.platform = :ios, '15.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'community_flutter_wifi_privacy' => ['community_flutter_wifi/Sources/community_flutter_wifi/PrivacyInfo.xcprivacy']}
end
