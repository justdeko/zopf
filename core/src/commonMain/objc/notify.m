#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#include <jni.h>

@interface ZopfResponder : NSObject <UNUserNotificationCenterDelegate>
@property(nonatomic, strong) NSCondition *condition;
@property(nonatomic, strong) NSMutableArray<NSString *> *answers;
@end

@implementation ZopfResponder

- (instancetype)init {
    if ((self = [super init])) {
        _condition = [NSCondition new];
        _answers = [NSMutableArray new];
    }
    return self;
}

// shown even while the app is active
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
                      UNNotificationPresentationOptionSound);
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
             withCompletionHandler:(void (^)(void))completionHandler {
    NSString *action = response.actionIdentifier;
    if (![action isEqualToString:UNNotificationDismissActionIdentifier]) {
        if ([action isEqualToString:UNNotificationDefaultActionIdentifier]) action = @"default";
        NSString *answer =
            [NSString stringWithFormat:@"%@\n%@", response.notification.request.identifier, action];
        [self.condition lock];
        [self.answers addObject:answer];
        [self.condition signal];
        [self.condition unlock];
    }
    completionHandler();
}

- (NSString *)next {
    [self.condition lock];
    while (self.answers.count == 0) [self.condition wait];
    NSString *answer = self.answers.firstObject;
    [self.answers removeObjectAtIndex:0];
    [self.condition unlock];
    return answer;
}

@end

static UNUserNotificationCenter *center;
static ZopfResponder *responder;
static NSMutableDictionary<NSString *, UNNotificationCategory *> *categories;

static NSString *text(JNIEnv *env, jstring value) {
    if (value == NULL) return @"";
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    NSString *result = [NSString stringWithCharacters:chars length:(*env)->GetStringLength(env, value)];
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static NSArray<NSString *> *texts(JNIEnv *env, jobjectArray values) {
    jsize count = (*env)->GetArrayLength(env, values);
    NSMutableArray<NSString *> *result = [NSMutableArray arrayWithCapacity:count];
    for (jsize index = 0; index < count; index++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, index);
        [result addObject:text(env, value)];
        (*env)->DeleteLocalRef(env, value);
    }
    return result;
}

// the center holds one set, so every open question's category goes in each time
static void publishCategories(void) {
    @synchronized(categories) {
        [center setNotificationCategories:[NSSet setWithArray:categories.allValues]];
    }
}

// outside an app bundle the center raises inside dispatch_once, which aborts past any @catch;
// a java launched from a .jdk still has a bundle id, of package type BNDL
JNIEXPORT jboolean JNICALL Java_com_dk_zopf_runtime_macos_MacNotifier_nativeStart(JNIEnv *env, jobject self) {
    @autoreleasepool {
        NSBundle *bundle = NSBundle.mainBundle;
        NSString *type = [bundle objectForInfoDictionaryKey:@"CFBundlePackageType"];
        if (bundle.bundleIdentifier == nil || ![type isEqualToString:@"APPL"]) return JNI_FALSE;
        center = UNUserNotificationCenter.currentNotificationCenter;
        responder = [ZopfResponder new];
        categories = [NSMutableDictionary new];
        center.delegate = responder;
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL Java_com_dk_zopf_runtime_macos_MacNotifier_nativePost(
    JNIEnv *env, jobject self, jstring jkey, jstring jtitle, jstring jsubtitle, jstring jbody, jstring jthread,
    jstring jsound, jobjectArray jactionIds, jobjectArray jactionLabels) {
    @autoreleasepool {
        NSString *key = text(env, jkey);
        UNMutableNotificationContent *content = [UNMutableNotificationContent new];
        content.title = text(env, jtitle);
        content.subtitle = text(env, jsubtitle);
        content.body = text(env, jbody);
        content.threadIdentifier = text(env, jthread);

        NSString *sound = text(env, jsound);
        if (sound.length > 0) {
            // this API wants Glass.aiff where osascript takes Glass
            NSString *named = [sound containsString:@"."] ? sound : [sound stringByAppendingString:@".aiff"];
            content.sound = [UNNotificationSound soundNamed:named];
        }

        NSArray<NSString *> *ids = texts(env, jactionIds);
        NSArray<NSString *> *labels = texts(env, jactionLabels);
        if (ids.count > 0) {
            NSMutableArray<UNNotificationAction *> *actions = [NSMutableArray arrayWithCapacity:ids.count];
            for (NSUInteger index = 0; index < ids.count; index++) {
                [actions addObject:[UNNotificationAction actionWithIdentifier:ids[index]
                                                                        title:labels[index]
                                                                      options:0]];
            }
            NSString *identifier = [@"zopf." stringByAppendingString:key];
            @synchronized(categories) {
                categories[identifier] = [UNNotificationCategory categoryWithIdentifier:identifier
                                                                                actions:actions
                                                                      intentIdentifiers:@[]
                                                                                options:0];
            }
            publishCategories();
            content.categoryIdentifier = identifier;
        }

        UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:key
                                                                              content:content
                                                                              trigger:nil];
        [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                              completionHandler:^(BOOL granted, NSError *error) {
                                if (!granted) {
                                    NSLog(@"zopf-notify: not allowed to notify %@",
                                          error.localizedDescription ?: @"");
                                    return;
                                }
                                [center addNotificationRequest:request
                                         withCompletionHandler:^(NSError *failure) {
                                           if (failure) NSLog(@"zopf-notify: %@", failure.localizedDescription);
                                         }];
                              }];
    }
}

JNIEXPORT void JNICALL Java_com_dk_zopf_runtime_macos_MacNotifier_nativeWithdraw(JNIEnv *env, jobject self,
                                                                                jstring jkey) {
    @autoreleasepool {
        NSString *key = text(env, jkey);
        [center removePendingNotificationRequestsWithIdentifiers:@[ key ]];
        [center removeDeliveredNotificationsWithIdentifiers:@[ key ]];
        NSString *identifier = [@"zopf." stringByAppendingString:key];
        BOOL removed = NO;
        @synchronized(categories) {
            removed = categories[identifier] != nil;
            [categories removeObjectForKey:identifier];
        }
        if (removed) publishCategories();
    }
}

// blocks until a click, as "<key>\n<action>"
JNIEXPORT jstring JNICALL Java_com_dk_zopf_runtime_macos_MacNotifier_nativeAwaitAnswer(JNIEnv *env, jobject self) {
    @autoreleasepool {
        NSString *answer = [responder next];
        NSUInteger length = answer.length;
        unichar *buffer = malloc(sizeof(unichar) * MAX(length, 1));
        [answer getCharacters:buffer range:NSMakeRange(0, length)];
        jstring result = (*env)->NewString(env, buffer, (jsize)length);
        free(buffer);
        return result;
    }
}
