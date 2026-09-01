package protoinit

import (
	"google.golang.org/protobuf/reflect/protoreflect"
)

type NoopRegistry struct{}

func (NoopRegistry) FindDescriptorByName(protoreflect.FullName) (protoreflect.Descriptor, error) {
	return nil, nil
}
func (NoopRegistry) FindFileByPath(string) (protoreflect.FileDescriptor, error) {
	return nil, nil
}
func (NoopRegistry) RegisterFile(protoreflect.FileDescriptor) error {
	return nil
}
func (NoopRegistry) RegisterEnum(protoreflect.EnumType) error {
	return nil
}
func (NoopRegistry) RegisterExtension(protoreflect.ExtensionType) error {
	return nil
}
func (NoopRegistry) RegisterMessage(protoreflect.MessageType) error {
	return nil
}
