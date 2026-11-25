#import "UploadTaskFactory.h"

NSString * const WRUploadTaskFactoryErrorDomain = @"WitnessRecord.UploadTaskCreation";

@implementation WRUploadTaskFactory

+ (nullable NSURLSessionUploadTask *)uploadTaskWithSession:(NSURLSession *)session
                                                   request:(NSURLRequest *)request
                                                  fromFile:(NSURL *)fileURL
                                                     error:(NSError **)error {
    @try {
        return [session uploadTaskWithRequest:request fromFile:fileURL];
    } @catch (NSException *exception) {
        if (error != NULL) {
            NSString *reason = exception.reason ?: exception.name ?: @"unknown";
            *error = [NSError errorWithDomain:WRUploadTaskFactoryErrorDomain
                                         code:1
                                     userInfo:@{ NSLocalizedDescriptionKey: reason }];
        }
        return nil;
    }
}

@end
