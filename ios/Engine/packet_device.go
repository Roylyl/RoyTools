package main

import (
	"context"
	"errors"
	"io"
	"time"

	"github.com/metacubex/gvisor/pkg/buffer"
	"github.com/metacubex/gvisor/pkg/tcpip"
	"github.com/metacubex/gvisor/pkg/tcpip/header"
	"github.com/metacubex/gvisor/pkg/tcpip/link/channel"
	"github.com/metacubex/gvisor/pkg/tcpip/stack"
	tun "github.com/metacubex/sing-tun"
)

type packetDevice struct {
	ctx      context.Context
	cancel   context.CancelFunc
	endpoint *channel.Endpoint
}

var _ tun.GVisorTun = (*packetDevice)(nil)

func newPacketDevice(mtu uint32) *packetDevice {
	ctx, cancel := context.WithCancel(context.Background())
	return &packetDevice{ctx: ctx, cancel: cancel, endpoint: channel.New(256, mtu, "")}
}

func (device *packetDevice) NewEndpoint() (stack.LinkEndpoint, stack.NICOptions, error) {
	return device.endpoint, stack.NICOptions{}, nil
}

func (device *packetDevice) WritePacket(packet *stack.PacketBuffer) (int, error) {
	var list stack.PacketBufferList
	list.PushBack(packet)
	n, err := device.endpoint.WritePackets(list)
	if err != nil {
		return 0, errors.New(err.String())
	}
	if n == 0 {
		return 0, io.ErrShortWrite
	}
	return packet.Size(), nil
}

func (device *packetDevice) Write(raw []byte) (int, error) {
	if device.ctx.Err() != nil {
		return 0, io.EOF
	}
	var protocol tcpip.NetworkProtocolNumber
	switch {
	case len(raw) >= 20 && raw[0]>>4 == 4:
		protocol = header.IPv4ProtocolNumber
	case len(raw) >= 40 && raw[0]>>4 == 6:
		protocol = header.IPv6ProtocolNumber
	default:
		return 0, errors.New("invalid IP packet")
	}
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(raw)})
	defer pkt.DecRef()
	device.endpoint.InjectInbound(protocol, pkt)
	return len(raw), nil
}

func (device *packetDevice) Read(out []byte) (int, error) {
	return device.read(device.ctx, out)
}

func (device *packetDevice) readPacket(out []byte, wait time.Duration) (int, error) {
	if wait == 0 {
		if device.ctx.Err() != nil {
			return 0, io.EOF
		}
		if pkt := device.endpoint.Read(); pkt != nil {
			return copyPacket(pkt, out)
		}
		return 0, context.DeadlineExceeded
	}
	ctx, cancel := context.WithTimeout(device.ctx, wait)
	defer cancel()
	return device.read(ctx, out)
}

func (device *packetDevice) read(ctx context.Context, out []byte) (int, error) {
	if device.ctx.Err() != nil {
		return 0, io.EOF
	}
	pkt := device.endpoint.ReadContext(ctx)
	if pkt == nil {
		if device.ctx.Err() != nil {
			return 0, io.EOF
		}
		return 0, ctx.Err()
	}
	return copyPacket(pkt, out)
}

func copyPacket(pkt *stack.PacketBuffer, out []byte) (int, error) {
	defer pkt.DecRef()
	body := pkt.ToView()
	defer body.Release()
	if body.Size() > len(out) {
		return 0, io.ErrShortBuffer
	}
	return copy(out, body.AsSlice()), nil
}

func (device *packetDevice) Close() error {
	device.cancel()
	device.endpoint.Close()
	return nil
}
