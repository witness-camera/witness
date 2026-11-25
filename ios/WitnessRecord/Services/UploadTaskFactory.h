#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Creates a background upload task without letting an Objective-C exception
/// take the process down.
///
/// `-[NSURLSession uploadTaskWithRequest:fromFile:]` raises rather than returns
/// an error when it cannot use the file. That is an NSException, which Swift
/// cannot catch at all, so it reaches the top of the stack and aborts the app —
/// during a recording, which is the worst possible moment.
///
/// Checking the file first in Swift is not enough, and shipping that taught us
/// why: the method barrier-syncs onto the session's own queue and does its real
/// check there, so any check beforehand races a queue we do not control.
///
/// The exception's reason is handed back in the NSError. Nothing in the crash
/// report said *why* the file was refused, which left the cause to guesswork;
/// now it lands in the event log instead.
@interface WRUploadTaskFactory : NSObject

+ (nullable NSURLSessionUploadTask *)uploadTaskWithSession:(NSURLSession *)session
                                                   request:(NSURLRequest *)request
                                                  fromFile:(NSURL *)fileURL
                                                     error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
