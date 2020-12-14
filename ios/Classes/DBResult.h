//
// Created by Daniil Kostin on 14.12.2020.
//

#import <Foundation/Foundation.h>

@interface DBResult : NSObject

@property (nonatomic, strong) NSMutableArray *arrResults;

@property (nonatomic, strong) NSMutableArray *arrColumnNames;

@property (nonatomic) int affectedRows;

@property (nonatomic) long long lastInsertedRowID;

@end
