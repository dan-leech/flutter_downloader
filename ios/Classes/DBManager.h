//
//  DBManager.h
//  Runner
//
//  Author: GABRIEL THEODOROPOULOS.
//

#import "DBResult.h"
#import <Foundation/Foundation.h>

@interface DBManager : NSObject

@property (nonatomic) BOOL debug;

-(instancetype)initWithDatabaseFilePath:(NSString *)dbFilePath;

-(DBResult *)loadDataFromDB:(NSString *)query;

-(DBResult *)executeQuery:(NSString *)query;

@end
