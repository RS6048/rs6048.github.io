// Java-js Bridge - custom TurboWarp extension (placeholder)
//
// This file is a placeholder. Replace it with the real extension implementation.
//
// It exposes the JS API that TurboWarp projects use to request local files
// through the JCEF bridge, for example:
//
//   twFile.read('assets/bg.png') -> Promise<string>  (base64 for binary files)
//
// The implementation forwards the request to the Java host via the CEF
// message router / JS binding, where the path is validated against an
// allow-listed root folder before the file is read.
