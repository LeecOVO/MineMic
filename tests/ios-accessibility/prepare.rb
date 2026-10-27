#!/usr/bin/env ruby
# The production project and signing settings are never modified.
require 'xcodeproj'
require 'fileutils'

root = File.expand_path('../..', __dir__)
output = File.join(root, 'build/ios-accessibility-host')
FileUtils.mkdir_p(output)
path = File.join(output, 'NativeControls.xcodeproj')
abort 'Test host project already exists' if File.exist?(path)
project = Xcodeproj::Project.new(path)
app = project.new_target(:application, 'NativeControlsHost', :ios, '18.2')
tests = project.new_target(:unit_test_bundle, 'NativeControlsTests', :ios, '18.2')
tests.add_dependency(app)
sources = project.main_group.new_group('Sources', File.join(root, 'tests/ios-accessibility'))
app.source_build_phase.add_file_reference(sources.new_file('Host.swift'))
tests.source_build_phase.add_file_reference(sources.new_file('NativeControlsTests.swift'))
framework_path = File.join(output, 'frameworks/NativeControlsTestSupport.framework')
framework = project.frameworks_group.new_file(framework_path)
tests.frameworks_build_phase.add_file_reference(framework)

[app, tests].each do |target|
  target.build_configurations.each do |configuration|
    configuration.build_settings.merge!({
      'PRODUCT_NAME' => target.name,
      'PRODUCT_BUNDLE_IDENTIFIER' => "com.aiaixyz.minemic.tests.#{target.name}",
      'SWIFT_VERSION' => '5.0',
      'GENERATE_INFOPLIST_FILE' => 'YES',
      'CODE_SIGNING_ALLOWED' => 'NO',
      'TARGETED_DEVICE_FAMILY' => '1',
      'ARCHS' => 'arm64',
      'ONLY_ACTIVE_ARCH' => 'YES',
      'SUPPORTS_MACCATALYST' => 'NO',
      'DEBUG_INFORMATION_FORMAT' => 'dwarf',
      'SWIFT_EMIT_LOC_STRINGS' => 'NO'
    })
  end
end
tests.build_configurations.each do |configuration|
  configuration.build_settings.merge!({
    'TEST_HOST' => '$(BUILT_PRODUCTS_DIR)/NativeControlsHost.app/NativeControlsHost',
    'BUNDLE_LOADER' => '$(TEST_HOST)',
    'FRAMEWORK_SEARCH_PATHS' => ['$(inherited)', File.join(output, 'frameworks')],
    'OTHER_LDFLAGS' => ['$(inherited)', '-ObjC', '-lc++']
  })
end
project.root_object.attributes['TargetAttributes'] = { tests.uuid => { 'TestTargetID' => app.uuid } }
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_build_target(tests)
scheme.add_test_target(tests)
scheme.test_action.build_configuration = 'Debug'
scheme.save_as(path, 'NativeControlsTests', true)
puts 'Prepared isolated UIKit application host and XCTest bundle.'
